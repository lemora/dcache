/* dCache - http://www.dcache.org/
 *
 * Copyright (C) 2023 Deutsches Elektronen-Synchrotron
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.dcache.trs;

import static java.util.concurrent.TimeUnit.SECONDS;

import diskCacheV111.util.PnfsId;
import dmg.cells.nucleus.CellInfoProvider;
import dmg.cells.nucleus.CellLifeCycleAware;
import dmg.cells.nucleus.CellMessageReceiver;
import java.io.PrintWriter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.dcache.cells.CellStub;
import org.dcache.cells.MessageReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Required;

/**
 * Cell core that schedules requests to leave the component based on time/request limit. Needs to be
 * separated into another class that accepts tape recall requests and returns them by means of
 * callbacks?
 */
public class Scheduler implements CellLifeCycleAware, CellMessageReceiver, CellInfoProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(Scheduler.class);

    private static final long MAX_JOB_RETRIEVALS_PER_RUN = 10;
    private static final long MIN_TIME_BETWEEN_QUERYING_STRATEGY = SECONDS.toMillis(30);

    private CellStub pinManagerStub;
    private SchedulingStrategy schedulingStrategy;
    private ConcurrentHashMap<String, TrsTask> jobidToMessage = new ConcurrentHashMap<>();

    // used to schedule requests leaving the scheduling strategy
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> retrieveNextJob;

    @Required
    public void setSchedulingStrategy(SchedulingStrategy strategy) {
        schedulingStrategy = strategy;
    }

    @Required
    public void setPinManagerStub(CellStub stub) {
        pinManagerStub = stub;
    }

    @Required
    public void setExecutor(ScheduledExecutorService executor) {
        this.executor = executor;
    }

    private void activateReturnedJob(long jobid) {
        // TODO: activate
    }

//    public void messageArrived(CellMessage envelope, TRSScheduleRequestMessage message) {
//        LOGGER.warn("Message arrived: " + message);
//        String requestid = message.getRequestId();
//        PnfsId pnfsid = message.getPnfsid();
//
//        SchedulingItemJob job = new SchedulingItemJob(requestid, pnfsid.toString());
//        String identifier = job.getIdentifier();
//
//        LOGGER.warn("Adding job with ob identifier {} to scheduler", identifier);
//
//        if (jobidToMessage.contains(identifier)) {
//            LOGGER.warn("Pin request key {} already scheduled", identifier);
//            return;
//        }
//        jobidToMessage.put(identifier, envelope);
//        schedulingStrategy.add(job);
//    }

    public MessageReply<TrsScheduleRequestMessage> messageArrived(
          TrsScheduleRequestMessage message) {
        LOGGER.warn("Message arrived: " + message);

        MessageReply<TrsScheduleRequestMessage> reply = new MessageReply<>();

        String requestid = message.getRequestId();
        PnfsId pnfsid = message.getPnfsid();

        TrsJob job = new TrsJob(requestid, pnfsid.toString());
        String identifier = job.getIdentifier();

        LOGGER.warn("Adding job with ob identifier {} to scheduler", identifier);

        if (jobidToMessage.contains(identifier)) {
            LOGGER.warn("Pin request key {} already scheduled", identifier);
            TrsTask task = jobidToMessage.get(identifier);
            // TODO!
        }
        TrsTask trsTask = new TrsTask(message, reply);

        jobidToMessage.put(identifier, trsTask);
        schedulingStrategy.add(job);

        return reply;
    }

    private void retrieveNextJobs() {
        LOGGER.warn("Retrieving next jobs...");

        for (long i = MAX_JOB_RETRIEVALS_PER_RUN; i > 0; i--) {
            String retrieved = schedulingStrategy.remove();
            if (retrieved == null) {
                break;
            }
            LOGGER.warn("Retrieved request with identifier {} from scheduler", retrieved);
//            replyToPinManager(retrieved);
            answerSchedulingRequestor(retrieved);
        }
    }

    private void answerSchedulingRequestor(String jobIdentifier) {
        if (!jobidToMessage.containsKey(jobIdentifier)
              || jobidToMessage.get(jobIdentifier) == null) {
            LOGGER.error("Request id retrieved from strategy but not present in scheduler!");
            return;
        }
        TrsTask trsTask = jobidToMessage.get(jobIdentifier);
        jobidToMessage.remove(jobIdentifier);
        trsTask.success();
    }

//    private void replyToPinManager(String jobIdentifier) {
//        if (!jobidToMessage.containsKey(jobIdentifier)
//              || jobidToMessage.get(jobIdentifier) == null) {
//            LOGGER.error("Request id retrieved from strategy but not present in scheduler!");
//            return;
//        }
//        CellMessage cellMsg = jobidToMessage.get(jobIdentifier);
//        jobidToMessage.remove(jobIdentifier);
//
//        TRSScheduleRequestMessage trsMsg = (TRSScheduleRequestMessage) cellMsg.getMessageObject();
//
//        PinManagerDescheduleMessage pinMsg = new PinManagerDescheduleMessage(
//              trsMsg.getFileAttributes(),
//              trsMsg.getProtocolInfo(), trsMsg.getRequestId(), trsMsg.getLifetime());
//
//        LOGGER.warn(
//              "Created PinManagerDescheduleMessage. Will attempt to send it to PinManager");
//
//        this.pinManagerStub.notify(pinMsg);
////        this.pinManagerStub.send(pinMsg);
//
////        ListenableFuture<PinManagerPinMessage> future = this.pinManagerStub.send(pinMsg);
////        future.addListener(() -> pinningSuccessful(), executor);
////        LOGGER.warn("Sent reply to PinManager: job {} left trs...", pinMsg);
//    }

    private void pinningSuccessful() {
        LOGGER.warn("Pinning successful!");
    }

    @Override
    public void afterStart() {
        // schedule job id removal from scheduling strategy
        LOGGER.warn("Starting the tape recall scheduler...");
        retrieveNextJob = executor.scheduleWithFixedDelay(this::retrieveNextJobs,
              MIN_TIME_BETWEEN_QUERYING_STRATEGY, MIN_TIME_BETWEEN_QUERYING_STRATEGY,
              TimeUnit.MILLISECONDS);
    }

    @Override
    public void beforeStop() {
        // cancel job id removal task
        LOGGER.warn("Stopping the tape recall scheduler...");
        if (retrieveNextJob != null) {
            retrieveNextJob.cancel(true);
        }
    }

    @Override
    public void getInfo(PrintWriter pw) {
        pw.println(schedulingStrategy.toString());
    }

}
