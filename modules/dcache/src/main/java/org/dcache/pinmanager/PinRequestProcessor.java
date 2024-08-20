package org.dcache.pinmanager;

import static org.dcache.pinmanager.model.Pin.State.PINNED;
import static org.dcache.pinmanager.model.Pin.State.PINNING;
import static org.dcache.pinmanager.model.Pin.State.READY_TO_PIN;

import diskCacheV111.util.CacheException;
import diskCacheV111.util.PnfsId;
import dmg.cells.nucleus.CellMessageReceiver;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;
import org.dcache.cells.MessageReply;
import org.dcache.pinmanager.model.Pin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Required;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processes pin requests. On arrival, create a DB entry in state READY_TO_PIN. The actual pinning
 * is handled by class `PinProcessor`.
 */
public class PinRequestProcessor implements CellMessageReceiver {

    private static final Logger LOGGER = LoggerFactory.getLogger(PinRequestProcessor.class);

    private PinDao _dao;

    @Required
    public void setDao(PinDao dao) {
        _dao = dao;
    }

    @Transactional
    public MessageReply<PinManagerPinMessage> messageArrived(PinManagerPinMessage message)
          throws CacheException {
        // TODO: check pinning permissions?
        LOGGER.info("Pin request arrived");

        MessageReply<PinManagerPinMessage> reply = new MessageReply<>();
        PnfsId pnfsId = message.getFileAttributes().getPnfsId();

        if (message.getRequestId() != null) {
            Pin pin = _dao.get(_dao.where().pnfsId(pnfsId).requestId(message.getRequestId()));
            if (pin != null) {
                // In this case the request is a resubmission
                if (pin.getState() == PINNED) {
                    LOGGER.info("Pin exists, returning it.");
                    // TODO: increase lifetime before returning!
                    message.setPin(pin);
                    reply.reply(message);
                    return reply;
                } else if (pin.getState() == PINNING) {
                    LOGGER.info("Pin is in progress, answering without pin id.");
                    message.setPinningInProgress(true);
                    reply.reply(message);
                    return reply;
                } else {
                    LOGGER.info("Expired pin exists, ignoring and creating a new pin request");
                    // clear request id so we can create a new pin
                    message.setPinningInProgress(true);
                    _dao.update(pin, _dao.set().state(READY_TO_PIN).requestId(null));
                }

            }
        }

        _dao.create(_dao.set()
              .subject(message.getSubject())
              .state(READY_TO_PIN)
              .pnfsId(pnfsId)
              .requestId(message.getRequestId())
              .sticky("PinManager-" + UUID.randomUUID().toString())
              .expirationTime(
                    new Date(System.currentTimeMillis() + Duration.ofHours(2).toMillis())));

        reply.reply(message);
        return reply;
    }

}
