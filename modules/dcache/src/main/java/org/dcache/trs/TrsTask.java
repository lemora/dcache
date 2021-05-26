package org.dcache.trs;

import java.util.Optional;
import org.dcache.cells.MessageReply;

public class TrsTask {

    private final TrsScheduleRequestMessage request;
    private final Optional<MessageReply<TrsScheduleRequestMessage>> reply;

    public TrsTask(TrsScheduleRequestMessage request,
          MessageReply<TrsScheduleRequestMessage> reply) {
        this.request = request;
        this.reply = Optional.of(reply);
    }

    public void fail(int rc, String error) {
        reply.ifPresent(r -> r.fail(request, rc, error));
    }

    public void success() {
        reply.ifPresent(r -> r.reply(request));
    }
}
