/* dCache - http://www.dcache.org/
 *
 * Copyright (C) 2021 - 2023 Deutsches Elektronen-Synchrotron
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

import static java.util.concurrent.TimeUnit.MINUTES;

import com.google.common.base.Strings;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.concurrent.GuardedBy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SRM scheduler for bring-online requests. The goal is to cluster requests by tape before
 * dispatching them.
 * <p>
 * The scheduler is a passive component that receives job IDs to be added to its queue and hands
 * them out in the desired sequence upon request.
 */
public class SchedulingStrategy {

    private static final Logger LOGGER = LoggerFactory.getLogger(
          SchedulingStrategy.class);

    private static final long MIN_TIME_BETWEEN_TAPEINFO_FETCHING = MINUTES.toMillis(1);

    private TrsRequirementsChecker requirementsChecker;
    private TapeInformant tapeInformant;
    private long lastTapeInfoRefreshAttempt = 0;

    // cached tape info and scheduling queues

    private final HashMap<String, SchedulingInfoTape> tapes = new HashMap<>();

    private final LinkedList<TrsJob> newJobs = new LinkedList<>();
    private final HashMap<String, LinkedList<TrsJob>> tapesWithJobs = new HashMap<>();
    private final HashMap<String, LinkedList<TrsJob>> activeTapesWithJobs = new HashMap<>();
    private final Queue<String> immediateJobQueue = new ArrayDeque<>();

    public void setRequirementsChecker(TrsRequirementsChecker moderator) {
        requirementsChecker = moderator;
    }

    public void setTapeInformant(TapeInformant informant) {
        tapeInformant = informant;
    }

//    public synchronized void add(Job job) {
//        String filename = ((BringOnlineFileRequest) job).getSurl().getRawPath();
//        BringOnlineJob jobInfo =  new BringOnlineJob(job.getId(), filename, job.getCreationTime());
//        addNewJob(jobInfo);
//        LOGGER.trace("Added bring-online job '{}' for file '{}' to scheduler", job.getId(), filename);
//    }

    public synchronized void add(TrsJob job) {
        addNewJob(job);
        LOGGER.trace("Added bring-online job '{}' for file '{}' to scheduler", job.getJobid(),
              job.getPnfsidString());
    }

    /**
     * Returns the id of the next job that should be executed
     *
     * @return String id of the next job
     */
    public synchronized String remove() {
        attemptToRefreshTapeInfo();

        boolean activatedTapes = false;
        if (!tapesWithJobs.isEmpty()
              && requirementsChecker.getRemainingTapeSlots(activeTapesWithJobs.size()) > 0) {
            activatedTapes = refillActiveTapeSlots();
        }

        if (activatedTapes) {
            logTapesWithJobsState();
        }

        if (immediateJobQueue.isEmpty()) {
            moveExpiredJobsToImmediateJobQueue();
            moveNextTapeJobsToImmediateJobQueue();
        }

        LOGGER.info(describeQueueState());

        String job = immediateJobQueue.poll();
        return job;
    }

    public synchronized int size() {
        int newJobsCount;
        int jobsByTapeCount;
        int activeJobsByTapeCount;
        int immediateJobsCount;
        newJobsCount = newJobs.size();
        jobsByTapeCount = tapesWithJobs.entrySet().stream().mapToInt(e -> e.getValue().size())
              .sum();
        activeJobsByTapeCount = activeTapesWithJobs.entrySet().stream()
              .mapToInt(e -> e.getValue().size()).sum();
        immediateJobsCount = immediateJobQueue.size();
        int overallCount =
              newJobsCount + jobsByTapeCount + activeJobsByTapeCount + immediateJobsCount;
        return overallCount;
    }

    @GuardedBy("this")
    private boolean addNewJob(TrsJob job) {
        return newJobs.add(job);
    }

    /**
     * Adds a job to the 'tapesWithJobs' hashmap, specifically into the list associated with the
     * given tape name. If the tape does not yet exist in the 'tapes' map, an entry is created that
     * does not yet have tape info.
     *
     * @param tape the tape the request is targeting
     * @param job  the job scheduling item that should be added to the tape list
     * @return if the job was added to the tape list
     */
    @GuardedBy("this")
    private boolean addJobToTapeQueue(TrsJob job, String tape) {
        if (tape == null || job == null || "".equals(tape)) {
            return false;
        }
        tapesWithJobs.computeIfAbsent(tape, k -> new LinkedList<>());
        SchedulingInfoTape tapeSchedItem = tapes.computeIfAbsent(tape,
              k -> new SchedulingInfoTape());
        long ctime = job.getCreationTime();
        tapeSchedItem.setNewestJobArrivalAndOldestIfNotExists(ctime);
        return tapesWithJobs.get(tape).add(job);
    }

    /*
     * Will check if there are free active tape slots and attempt to fill them by requesting
     * to receive the next tapes to activate.
     */
    @GuardedBy("this")
    private boolean refillActiveTapeSlots() {
        boolean activatedTapes = false;
        int freeTapeSlots = requirementsChecker.getRemainingTapeSlots(activeTapesWithJobs.size());

        while (freeTapeSlots > 0) {
            String tape = selectNextTapeToActivate();
            if (Strings.isNullOrEmpty(tape)) {
                break;
            }
            LinkedList<TrsJob> jobs = tapesWithJobs.remove(tape);
            tapes.get(tape).resetJobArrivalTimes();
            String actionTaken;
            if (activeTapesWithJobs.containsKey(tape) && activeTapesWithJobs.get(tape) != null) {
                activeTapesWithJobs.get(tape).addAll(jobs);
                actionTaken = "Added jobs to active tape";
            } else {
                activeTapesWithJobs.put(tape, jobs);
                actionTaken = "ACTIVATED TAPE";
            }
            LOGGER.info("{} {} with {} jobs", actionTaken, tape, jobs.size());
            freeTapeSlots--;
            activatedTapes = true;
        }
        return activatedTapes;
    }

    /**
     * Core of the scheduler: Tape selection logic
     * <p>
     * Checks for and selects a tape with expired requests first (the one with the oldest one), then
     * narrows in on tapes which have not had new job additions too recently and then checks if
     * there is a tape with sufficiently large recall volume. otherwise, if configured, checks and
     * returns a tape with an acceptable queue size
     *
     * @return the next best tape to activate; else null
     */
    @GuardedBy("this")
    private String selectNextTapeToActivate() {
        Comparator<String> tapeComparatorOldestRequest = (String t1, String t2) -> requirementsChecker.compareOldestTapeRequestAge(
              tapes.get(t1), tapes.get(t2));
        Comparator<String> tapeComparatorLargestQueue = (String t1, String t2) -> Integer.compare(
              tapesWithJobs.get(t2).size(), tapesWithJobs.get(t1).size());

        if (tapesWithJobs.isEmpty()) {
            LOGGER.trace("No tapes available for activating");
            return null;
        }

        // tapes that have jobs + are not already selected

        List<String> eligibleTapes = tapesWithJobs.keySet().stream()
              .filter(t -> !activeTapesWithJobs.containsKey(t))
              .collect(Collectors.toList());

        if (eligibleTapes.isEmpty()) {
            LOGGER.trace("No tapes available for activating");
            return null;
        }

        // check for/select tapes with expired jobs first

        List<String> tapesWithExpiredRequests = eligibleTapes.stream()
              .filter(t -> requirementsChecker.isOldestTapeJobExpired(
                    tapes.get(t))) // selecting all expired jobs
              .collect(Collectors.toList());

        if (!tapesWithExpiredRequests.isEmpty()) {
            tapesWithExpiredRequests.sort(tapeComparatorOldestRequest);
            String t = tapesWithExpiredRequests.get(0);
            LOGGER.info("Selecting tape with expired requests: {}", t);
            return t;
        }

        // limit eligible tapes to those where most recent job has surpassed min job waiting time

        eligibleTapes = eligibleTapes.stream()
              .filter(t -> requirementsChecker.isNewestTapeJobOldEnough(tapes.get(t)))
              .collect(Collectors.toList());

        if (eligibleTapes.isEmpty()) {
            LOGGER.trace("No tapes available whose last job arrival is not too recent.");
            return null;
        }

        // check for/get tapes with (sufficient) recall volume next

        Map<String, Long> tapesWithRecallVolume = eligibleTapes.stream()
              .collect(Collectors.toMap(Function.identity(),
                    t -> tapesWithJobs.get(t).stream()
                          .map(TrsJob::getFileSize)
                          .filter(OptionalLong::isPresent)
                          .mapToLong(OptionalLong::getAsLong)
                          .sum()));

        List<String> tapesWithSufficientRecallVolume = tapesWithRecallVolume.entrySet().stream()
              .filter(e -> requirementsChecker.isTapeRecallVolumeSufficient(tapes.get(e.getKey()),
                    e.getValue()))
              .sorted(Comparator.comparingLong(Map.Entry::getValue))
              .map(Map.Entry::getKey)
              .collect(Collectors.toList());

        if (!tapesWithSufficientRecallVolume.isEmpty()) {
            String t = tapesWithSufficientRecallVolume.get(
                  tapesWithSufficientRecallVolume.size() - 1);
            LOGGER.info("Selecting tape with sufficient recall volume: {}", t);
            return t;
        }

        // if configured, finish with checking if a tape has a sufficiently long job queue

        if (!requirementsChecker.isDefinedMinRequestCount()) {
            LOGGER.trace("No tapes available with sufficient recall volume.");
            return null;
        }

        String tapeWithLongestQueue = eligibleTapes.stream()
              .sorted(tapeComparatorLargestQueue)
              .findFirst()
              .get();

        boolean queueSufficientlyLong = requirementsChecker.isRequestCountSufficient(
              tapesWithJobs.get(tapeWithLongestQueue).size());
        LOGGER.info("Found {}tape with sufficiently long job queue.",
              (queueSufficientlyLong ? "" : "no "));
        return queueSufficientlyLong ? tapeWithLongestQueue : null;
    }

    /**
     * Moves the first job of every tape in 'activeTapesWithJobs' into the 'immediateJobQueue'.
     * Removes tapes without remaining jobs from 'activeTapesWithJobs' and if there are no remaining
     * requests targeting the tape in general, it will be removed from the 'tapes' map as well.
     */
    @GuardedBy("this")
    private void moveNextTapeJobsToImmediateJobQueue() {
        if (activeTapesWithJobs.isEmpty()) {
            return;
        }

        List<TrsJob> jobsToMove = new LinkedList<>();

        Iterator<Map.Entry<String, LinkedList<TrsJob>>> itr = activeTapesWithJobs.entrySet()
              .iterator();

        while (itr.hasNext()) {
            var currentTape = itr.next();
            var tapeName = currentTape.getKey();
            var jobs = currentTape.getValue();

            if (!jobs.isEmpty()) { // jobs left for tape
                TrsJob job = jobs.remove();
                jobsToMove.add(job);
            }

            if (jobs.isEmpty()) {
                if (tapesWithJobs.containsKey(tapeName)) {
                    tapes.get(tapeName).resetJobArrivalTimes();
                } else {
                    // remove tape if there are no jobs targeting it
                    tapes.remove(tapeName);
                }
                itr.remove();
            } else { // update oldest job arrival time
                tapes.get(tapeName)
                      .setOldestJobArrival(jobs.element().getCreationTime());
            }
        }

        jobsToMove.sort(Comparator.comparingLong(
              TrsJob::getCreationTime)); // sorting by ctime to have predictable ordering
        jobsToMove.forEach(j -> immediateJobQueue.add(j.getIdentifier()));
    }

    /**
     * Retrieves all jobs without tape info that have exceeded the max queue waiting time and adds
     * them to the 'immediateJobQueue'.
     */
    @GuardedBy("this")
    private void moveExpiredJobsToImmediateJobQueue() {
        List<String> expiredJobs = new LinkedList<>();
        Iterator<TrsJob> iterator = newJobs.iterator();
        TrsJob job;

        while (iterator.hasNext()) {
            job = iterator.next();
            if (job.attemptedToRetrieveTapeLocationInfo()
                  && requirementsChecker.isTapeinfolessJobExpired(job)) {
                expiredJobs.add(job.getIdentifier());
                iterator.remove();
            }
        }
        if (!expiredJobs.isEmpty()) {
            LOGGER.info("Added {} expired jobs without tape info to the immediate queue",
                  expiredJobs.size());
        }
        immediateJobQueue.addAll(expiredJobs);
    }

    /**
     * Attempts to refresh tape location information for requests and tapes if enough time has
     * passed since the last attempt and new jobs exists.
     */
    @GuardedBy("this")
    private void attemptToRefreshTapeInfo() {
        long current = System.currentTimeMillis();
        if (current > lastTapeInfoRefreshAttempt + MIN_TIME_BETWEEN_TAPEINFO_FETCHING) {
            fetchAndAddTapeInfoForJobs();
            fetchAndAddInfosForTapes();
            lastTapeInfoRefreshAttempt = System.currentTimeMillis();
        }
    }

    /**
     * Adds tape information to a tape scheduling info item in the 'tapes' map.
     *
     * @param tape      the tape's name
     * @param capacity  the tape's capacity
     * @param usedSpace the tape's used space
     */
    private void addTapeInfo(String tape, long capacity, long usedSpace) {
        tapes.computeIfAbsent(tape, t -> new SchedulingInfoTape()).addTapeInfo(capacity, usedSpace);
    }

    /**
     * Creates a list of file names from the 'newJobs' list, sends that to the 'tapeInfoProvider' to
     * get the missing tape location infos, adds them to the job scheduling item. It is then removed
     * from 'newJobs' and added to the 'tapesWithJobs' map.
     */
    @GuardedBy("this")
    private void fetchAndAddTapeInfoForJobs() {
        if (newJobs.isEmpty()) {
            return;
        }
        Set<String> changedTapeQueues = new HashSet();

        List<String> pnfsids = newJobs.stream()
              .map(i -> {
                  i.setAttemptedToRetrieveTapeLocationInfo();
                  return i.getPnfsidString();
              })
              .collect(Collectors.toList());

        Map<String, FileInfo> newTapeFileInfos = tapeInformant.getTapefileInfos(pnfsids);
        LOGGER.info("Retrieved tape info on {}/{} files", newTapeFileInfos.size(), pnfsids.size());

        Iterator<TrsJob> iterator = newJobs.iterator();
        TrsJob job;
        String pnfsid;
        FileInfo tapeFileInfo;

        while (iterator.hasNext()) {
            job = iterator.next();
            pnfsid = job.getPnfsidString();

            if (newTapeFileInfos.containsKey(pnfsid)) {
                tapeFileInfo = newTapeFileInfos.get(pnfsid);
                job.setFileSize(tapeFileInfo.getFilesize());

                if (addJobToTapeQueue(job, tapeFileInfo.getTapename())) {
                    changedTapeQueues.add(tapeFileInfo.getTapename());
                    iterator.remove();
                }
            }
        }
        sortTapeRequestQueues(changedTapeQueues);
    }

    /**
     * Sorts all lists in the 'tapesWithJobs' map whose key is contained in the given list of tape
     * names.
     *
     * @param tapes the names of tapes for which to sort the corresponding request queues
     */
    @GuardedBy("this")
    private void sortTapeRequestQueues(Set<String> tapes) {
        tapes.forEach(t -> sortTapeQueue(t));
    }

    /**
     * Sorts the request queue for a tape in 'tapesWithJobs' according to their request creation age
     * in ascending order. It then updates the oldest and newest job creation times in the
     * corresponding tape object from the 'tapes' map.
     *
     * @param tapeName the name of the tape for which to sort the corresponding request queue
     */
    @GuardedBy("this")
    private void sortTapeQueue(String tapeName) {
        if (!tapesWithJobs.containsKey(tapeName)) {
            return;
        }
        List<TrsJob> tapeJobs = tapesWithJobs.get(tapeName);
        tapeJobs.sort(Comparator.comparingLong(TrsJob::getCreationTime));

        SchedulingInfoTape tape = tapes.get(tapeName);
        tape.resetJobArrivalTimes();
        if (!tapeJobs.isEmpty()) {
            tape.setOldestJobArrival(tapeJobs.get(0).getCreationTime());
            tape.setNewestJobArrival(tapeJobs.get(tapeJobs.size() - 1).getCreationTime());
        }
    }

    /**
     * Creates a list of tape names from the 'tapes' map where the associated tape info is missing,
     * sends that to the 'tapeInfoProvider' to get the missing infos and feeds it back into the
     * corresponding entries in 'tapes'.
     */
    @GuardedBy("this")
    private void fetchAndAddInfosForTapes() {
        List<String> tapesWithoutInfo = tapes.entrySet().stream()
              .filter(e -> !e.getValue().hasTapeInfo()).map(e -> e.getKey())
              .collect(Collectors.toList());

        if (tapesWithoutInfo.isEmpty()) {
            return;
        }
        Map<String, TapeInfo> newInfo = tapeInformant.getTapeInfos(tapesWithoutInfo);
        LOGGER.info("Retrieved info on {}/{} tapes", newInfo.size(), tapesWithoutInfo.size());

        newInfo.entrySet().stream().forEach(e -> addTapeInfo(e.getKey(), e.getValue().getCapacity(),
              e.getValue().getUsedSpace()));
    }

    @GuardedBy("this")
    private String describeQueueState() {
        StringBuilder sb = new StringBuilder();
        int newJobsCount = newJobs.size();
        int jobsByTapeCount = tapesWithJobs.entrySet().stream().mapToInt(e -> e.getValue().size())
              .sum();
        int activeJobsByTapeCount = activeTapesWithJobs.entrySet().stream()
              .mapToInt(e -> e.getValue().size()).sum();
        int immediateJobsCount = immediateJobQueue.size();

        int overallCount =
              newJobsCount + jobsByTapeCount + activeJobsByTapeCount + immediateJobsCount;

        sb.append("New jobs: ").append(newJobsCount);
        sb.append(" | jobs by tape: ").append(jobsByTapeCount);
        sb.append(" | jobs from active tapes: ").append(activeJobsByTapeCount);
        sb.append(" | immediate jobs: ").append(immediateJobsCount);
        sb.append(" | SUM: ").append(overallCount);
        return sb.toString();
    }

    @GuardedBy("this")
    public String describeTapesWithJobsMap(Map<String, LinkedList<TrsJob>> tapesJobMap) {
        StringBuilder sb = new StringBuilder();
        if (tapesJobMap.isEmpty()) {
            return sb.append(" []").toString();
        }
        tapesJobMap.entrySet().stream().forEach(
              e -> sb.append("(").append(e.getKey()).append(", ").append(e.getValue().size())
                    .append(") "));
        return sb.toString();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(describeQueueState()).append("\n");
        sb.append("Pending tapes: ").append(describeTapesWithJobsMap(tapesWithJobs)).append("\n");
        sb.append("Active tapes: ").append(describeTapesWithJobsMap(activeTapesWithJobs));
        return sb.toString();
    }

    private void logTapesWithJobsState() {
        if (!tapesWithJobs.isEmpty()) {
            LOGGER.info("Pending tapes: {}", describeTapesWithJobsMap(tapesWithJobs));
        }
        if (!activeTapesWithJobs.isEmpty()) {
            LOGGER.info("Active tapes: {}", describeTapesWithJobsMap(activeTapesWithJobs));
        }
    }

}
