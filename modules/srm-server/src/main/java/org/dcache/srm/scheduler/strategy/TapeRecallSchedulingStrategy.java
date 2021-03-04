/* dCache - http://www.dcache.org/
 *
 * Copyright (C) 2021 Deutsches Elektronen-Synchrotron
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

package org.dcache.srm.scheduler.strategy;

import com.google.common.base.Strings;
import org.dcache.srm.request.BringOnlineFileRequest;
import org.dcache.srm.request.Job;
import org.dcache.srm.scheduler.spi.SchedulingStrategy;
import org.dcache.srm.taperecallscheduling.TapeRecallSchedulingRequirementsChecker;
import org.dcache.srm.taperecallscheduling.SchedulingItemJob;
import org.dcache.srm.taperecallscheduling.SchedulingInfoTape;
import org.dcache.srm.taperecallscheduling.TapeInfo;
import org.dcache.srm.taperecallscheduling.TapeInfoProvider;
import org.dcache.srm.taperecallscheduling.TapefileInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.MINUTES;

/**
 * SRM scheduler for bring-online requests.
 * The goal is to cluster requests by tape before dispatching them.
 *
 * The scheduler is a passive component that receives job IDs to be added to its queue
 * and hands them out in the desired sequence upon request.
 */
public class TapeRecallSchedulingStrategy implements SchedulingStrategy {
    private static final Logger LOGGER = LoggerFactory.getLogger(TapeRecallSchedulingStrategy.class);

    private static final long MIN_TIME_BETWEEN_TAPEINFO_FETCHING = MINUTES.toMillis(2);
    private static final Object LOCK = new Object();

    private TapeRecallSchedulingRequirementsChecker requirementsChecker;
    private TapeInfoProvider tapeInfoProvider;
    private long lastTapeInfoFetch = 0;

    // cached tape info and scheduling queues

    private final HashMap<String, SchedulingInfoTape> tapes = new HashMap<>();

    private final LinkedList<SchedulingItemJob> newJobs = new LinkedList<>();
    private final HashMap<String, LinkedList<SchedulingItemJob>> tapesWithJobs = new HashMap<>();
    private final HashMap<String, LinkedList<SchedulingItemJob>> activeTapesWithJobs = new HashMap<>();
    private final Queue<Long> immediateJobQueue = new ArrayDeque<>();

    public void setRequirementsChecker(TapeRecallSchedulingRequirementsChecker moderator) {
        requirementsChecker = moderator;
    }

    public void setTapeInfoProvider(TapeInfoProvider provider) {
        this.tapeInfoProvider = provider;
    }

    @Override
    public void add(Job job) {
        String filename = ((BringOnlineFileRequest) job).getSurl().getRawPath();
        SchedulingItemJob jobInfo =  new SchedulingItemJob(job.getId(), filename, job.getCreationTime());
        addNewJob(jobInfo);
        LOGGER.info("Added bring-online job '{}' for file '{}' to scheduler", job.getId(), filename);
    }

    @Override
    public Long remove() {
        synchronized (LOCK) {
            if (System.currentTimeMillis() > lastTapeInfoFetch + MIN_TIME_BETWEEN_TAPEINFO_FETCHING) {
                fetchTapeInfo();
            }

            if (activeTapesWithJobs.size() < requirementsChecker.maxActiveTapes()) {
                LOGGER.info("Attempting to refill unused tape slots (filled: {}/{}).", activeTapesWithJobs.size(), requirementsChecker.maxActiveTapes());
                refillActiveTapeSlots();
            }

            if (immediateJobQueue.isEmpty()) {
                moveExpiredJobsToImmediateJobQueue();
                moveNextTapeJobsToImmediateJobQueue();
            }

            LOGGER.info(getStateInfo());

            Long job = immediateJobQueue.poll();
            LOGGER.info("Immediate queue size: {}. Returning job: {}", immediateJobQueue.size(), job);
            return job;
        }
    }

    @Override
    public int size() {
        int newJobsCount;
        int jobsByTapeCount;
        int activeJobsByTapeCount;
        int immediateJobsCount;
        synchronized (LOCK) {
            newJobsCount = newJobs.size();
            jobsByTapeCount = tapesWithJobs.entrySet().stream().mapToInt(e -> e.getValue().size()).sum();
            activeJobsByTapeCount = activeTapesWithJobs.entrySet().stream().mapToInt(e -> e.getValue().size()).sum();
            immediateJobsCount = immediateJobQueue.size();
        }
        int overallCount = newJobsCount + jobsByTapeCount + activeJobsByTapeCount + immediateJobsCount;
        return overallCount;
    }

    private boolean addNewJob(SchedulingItemJob job) {
        synchronized (LOCK) {
            return newJobs.add(job);
        }
    }

    /**
     * Adds a job to the jobsByTape hashmap into the list for the associated tape name.
     * If the tape does not yet exist in the "tapes" map, an entry is created, flagged as missing tape ifo.
     * @param tape
     * @param job
     * @return if the job was added
     */
    private boolean addTapeJob(SchedulingItemJob job, String tape) {
        if (tape == null || job == null || "".equals(tape)) {
            return false;
        }
        synchronized (LOCK) {
            tapesWithJobs.computeIfAbsent(tape, k -> new LinkedList<>());
            tapes.computeIfAbsent(tape, k -> new SchedulingInfoTape());

            // update tape scheduling info
            SchedulingInfoTape tapeSchedItem = tapes.get(tape);
            tapeSchedItem.setNewestJobArrival(job.getCreationTime());
            return tapesWithJobs.get(tape).add(job);
        }
    }

    /*
     * Will check all tapes that are currently active: are their job queues empty, should they be replaced?
     * Can free tape slots be filled?
     */
    private void refillActiveTapeSlots() {
        int freeTapeSlots = requirementsChecker.maxActiveTapes() - activeTapesWithJobs.size();

        while (freeTapeSlots > 0) {
            String tape = selectNextTapeToActivate();
            if (Strings.isNullOrEmpty(tape)) {
                break;
            }
            synchronized (LOCK) {
                LinkedList<SchedulingItemJob> jobs = tapesWithJobs.remove(tape);
                tapes.get(tape).resetJobArrivalTimes();
                if (activeTapesWithJobs.containsKey(tape) && activeTapesWithJobs.get(tape) != null) {
                    activeTapesWithJobs.get(tape).addAll(jobs);
                } else {
                    activeTapesWithJobs.put(tape, jobs);
                }
                LOGGER.info("--- ACTIVATED TAPE {} with {} jobs", tape, jobs.size());
            }
            freeTapeSlots--;
        }
    }

    /**
     * Core of the scheduler: Tape selection logic
     *
     * Checks for and selects tape with expired jobs first (the one with the oldest job first),
     * then narrows in on tapes which have not had new job additions too recently and
     * then checks if there is a tape with sufficiently large recall volume.
     * otherwise, if configured, checks and returns if/when a tape has reached an acceptable queue size
     *
     * @return the next best tape to activate; else null
     */
    private String selectNextTapeToActivate() {
        Comparator<String> tapeComparatorOldestRequest = (String t1, String t2) -> requirementsChecker.compareOldestTapeRequestAge(tapes.get(t1), tapes.get(t2));
        Comparator<String> tapeComparatorLargestQueue = (String t1, String t2) -> Integer.compare(tapesWithJobs.get(t2).size(), tapesWithJobs.get(t1).size());

        if (tapesWithJobs.isEmpty()) {
            LOGGER.info("No tapes available for activating");
            return null;
        }

        synchronized (LOCK) {
            // tapes that have jobs + are not already selected

            List<String> eligibleTapes = tapesWithJobs.keySet().stream()
                    .filter(t -> !activeTapesWithJobs.containsKey(t))
                    .collect(Collectors.toList());


            if (eligibleTapes.isEmpty()) {
                LOGGER.info("No tapes with jobs that are not already activated available");
                return null;
            }

            // check for/select tapes with expired jobs first

            List<String> tapesWithExpiredRequests = eligibleTapes.stream()
                    .filter(t -> requirementsChecker.isOldestTapeJobExpired(tapes.get(t))) // selecting all expired jobs
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
                LOGGER.info("No tapes available whose last job arrival is not too recent.");
                return null;
            }

            // check for/get tapes with (sufficient) recall volume next

            Map<String, Long> tapesWithRecallVolume = eligibleTapes.stream()
                    .collect(Collectors.toMap(Function.identity(),
                            t -> tapesWithJobs.get(t).stream().mapToLong(i -> i.getFileSize()).sum()));

            List<String> tapesWithSufficientRecallVolume = tapesWithRecallVolume.entrySet().stream()
                    .filter(e -> requirementsChecker.isTapeRecallVolumeSufficient(tapes.get(e.getKey()), e.getValue()))
                    .sorted(Comparator.comparingLong(Map.Entry::getValue))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());

            if (!tapesWithSufficientRecallVolume.isEmpty()) {
                String t = tapesWithSufficientRecallVolume.get(tapesWithSufficientRecallVolume.size()-1);
                LOGGER.info("Selecting tape with sufficient recall volume: {}", t);
                return t;
            }

            // if configured, finish with checking if a tape has a sufficiently long job queue

            if (requirementsChecker.minNumberOfRequestsForTapeSelection() == requirementsChecker.NO_VALUE) {
                return null;
            }

            String tapeWithLongestQueue = eligibleTapes.stream()
                    .sorted(tapeComparatorLargestQueue)
                    .findFirst()
                    .get();

            boolean queueSufficientlyLong = tapesWithJobs.get(tapeWithLongestQueue).size() >= requirementsChecker.minNumberOfRequestsForTapeSelection();
            LOGGER.info("Found {}tape with sufficiently long job queue.", (queueSufficientlyLong ? "":"no "));
            return queueSufficientlyLong ? tapeWithLongestQueue : null;
        }
    }

    /**
     * moves the first job of every active tape (from activeTapesWithJobs) into the immediate job queue.
     * removes tapes without remaining jobs from activeTapesWithJobs
     */
    private void moveNextTapeJobsToImmediateJobQueue() {
        synchronized (LOCK) {
            if (activeTapesWithJobs.isEmpty()) {
                LOGGER.info("No tapes active for moving jobs to the immediate queue");
                return;
            }

            List<SchedulingItemJob> jobsToMove =  new LinkedList<>();

            Iterator<Map.Entry<String, LinkedList<SchedulingItemJob>>> activeTapesWithJobsIterator = this.activeTapesWithJobs.entrySet().iterator();
            Map.Entry<String, LinkedList<SchedulingItemJob>> currTapeWithJobs;

            while (activeTapesWithJobsIterator.hasNext()) {
                currTapeWithJobs = activeTapesWithJobsIterator.next();

                if (!currTapeWithJobs.getValue().isEmpty()) { // jobs left for tape
                    SchedulingItemJob currJobInfo = currTapeWithJobs.getValue().remove();
                    jobsToMove.add(currJobInfo);
                    LOGGER.info("Added job {} for file {} from tape {} to immediate queue", currJobInfo.getJobid(), currJobInfo.getFileid(), currTapeWithJobs.getKey());
                }

                if (currTapeWithJobs.getValue().isEmpty()) { // no jobs remaining for tape: remove tape from being active
                    LOGGER.info("No jobs left for tape {}; removing it from immediate processing", currTapeWithJobs.getKey());
                    tapes.get(currTapeWithJobs.getKey()).resetJobArrivalTimes();
                    activeTapesWithJobsIterator.remove();
                } else { // update oldest job arrival time
                    tapes.get(currTapeWithJobs.getKey()).setOldestJobArrival(currTapeWithJobs.getValue().element().getCreationTime()); // TODO: not necessarily ordered by request age?!
                }
            }

            jobsToMove.sort(Comparator.comparingLong(SchedulingItemJob::getCreationTime)); // sorting by ctime to have predictable ordering
            jobsToMove.forEach(j -> immediateJobQueue.add(j.getJobid()));
        }
    }

    /**
     * retrieves all jobs without tape info that have exceeded the max queue waiting time and adds them to the "immediateJobQueue"
     */
    private void moveExpiredJobsToImmediateJobQueue() {
        synchronized (LOCK) {
            List<Long> expiredJobs = new LinkedList<>();
            Iterator<SchedulingItemJob> iterator = newJobs.iterator();
            SchedulingItemJob job;

            while (iterator.hasNext()) {
                job = iterator.next();
                if (requirementsChecker.isJobExpired(job)) {
                    expiredJobs.add(job.getJobid());
                    iterator.remove();
                }
            }
            LOGGER.info("Added {} expired jobs to immediate queue", expiredJobs.size());
            immediateJobQueue.addAll(expiredJobs);
        }
    }

    // -------- Helper methods for enriching jobs with tape specific information for scheduler

    private void fetchTapeInfo() {
        if (!newJobs.isEmpty() || tapeInfoProvider == null) {
            LOGGER.info("Fetching tape location info");
            fetchAndAddTapeInfoForJobs();
            fetchAndAddInfosForTapes();
        }
        lastTapeInfoFetch = System.currentTimeMillis();
    }

    private void addTapeInfo(String tape, long capacity, long usedSpace) {
        tapes.computeIfAbsent(tape, t -> new SchedulingInfoTape()).addTapeInfo(capacity, usedSpace);
    }

    /**
     * Creates a list of file names from the 'newJobs' list, sends that to the
     * tapeInfoProvider to get the missing tape location infos, adds them to the BringOnlineSchedulingItemJob,
     * which is then removed from 'newJobs' and added to the job list for the discovered tape in the
     * 'jobsByTape' map.
     */
    private void fetchAndAddTapeInfoForJobs() {
        LOGGER.info("-- Fetching tape infos for FILES");

        synchronized (LOCK) {
            if (newJobs.size() == 0) {
                LOGGER.info("There are no job target files without tape info");
                return;
            }
            Set<String> changedTapeQueues = new HashSet();

            List<String> fileids = newJobs.stream().map(j -> j.getFileid()).collect(Collectors.toList());

            Map<String, TapefileInfo> newTapeFileInfos = tapeInfoProvider.getTapefileInfos(fileids);
            LOGGER.info("Retrieved tape info on {}/{} files", newTapeFileInfos.size(), fileids.size());

            Iterator<SchedulingItemJob> iterator = newJobs.iterator();
            SchedulingItemJob job;
            String fileid;
            TapefileInfo tapeFileInfo;

            while (iterator.hasNext()) {
                job = iterator.next();
                fileid = job.getFileid();

                if (newTapeFileInfos.containsKey(fileid)) {
                    tapeFileInfo = newTapeFileInfos.get(fileid);
                    job.setFileSize(tapeFileInfo.getFilesize());

                    if (addTapeJob(job, tapeFileInfo.getTapename())) {
                        changedTapeQueues.add(tapeFileInfo.getTapename());
                        iterator.remove();
                    }
                }
            }
            sortTapeRequestQueues(changedTapeQueues);
        }
    }

    private void sortTapeRequestQueues(Set<String> tapes) {
        tapes.forEach(t -> sortTapeQueue(t));
    }

    private void sortTapeQueue(String tapeName) {
        if (!tapesWithJobs.containsKey(tapeName)) {
            return;
        }
        synchronized (LOCK) {
            List<SchedulingItemJob> tapeJobs = tapesWithJobs.get(tapeName);
            tapeJobs.sort(Comparator.comparingLong(SchedulingItemJob::getCreationTime));

            SchedulingInfoTape tape = tapes.get(tapeName);
            tape.resetJobArrivalTimes();
            if (!tapeJobs.isEmpty()) {
                tape.setOldestJobArrival(tapeJobs.get(0).getCreationTime());
                tape.setNewestJobArrival(tapeJobs.get(tapeJobs.size() - 1).getCreationTime());
            }
        }
    }

    /**
     * Creates a list of tape names (keys) from 'tapes' where the associated tape info is missing, sending that to the
     * tapeInfoProvider to get the missing infos and adding it to the entries in 'tapes'.
     */
    private void fetchAndAddInfosForTapes() {
        LOGGER.info("-- Fetching infos on TAPES");

        synchronized (LOCK) {
            List<String> tapesWithoutInfo = tapes.entrySet().stream().filter(e -> !e.getValue().hasTapeInfo()).map(e -> e.getKey()).collect(Collectors.toList());

            if (tapesWithoutInfo.size() == 0) {
                LOGGER.info("There are no tapes without tape system info");
                return;
            }
            Map<String, TapeInfo> newInfo = tapeInfoProvider.getTapeInfos(tapesWithoutInfo);
            LOGGER.info("Retrieved info on {}/{} tapes", newInfo.size(), tapesWithoutInfo.size());

            newInfo.entrySet().stream().forEach(e -> addTapeInfo(e.getKey(), e.getValue().getCapacity(), e.getValue().getUsedSpace()));
        }
    }

    public String getStateInfo() {
        StringBuilder sb = new StringBuilder();
        int newJobsCount;
        int jobsByTapeCount;
        int activeJobsByTapeCount;
        int immediateJobsCount;
        int overallCount;
        synchronized (LOCK) {
            newJobsCount = newJobs.size();
            jobsByTapeCount = tapesWithJobs.entrySet().stream().mapToInt(e -> e.getValue().size()).sum();
            activeJobsByTapeCount = activeTapesWithJobs.entrySet().stream().mapToInt(e -> e.getValue().size()).sum();
            immediateJobsCount = immediateJobQueue.size();

            overallCount = newJobsCount + jobsByTapeCount + activeJobsByTapeCount + immediateJobsCount;

            sb.append("New jobs: ").append(newJobsCount);
            sb.append(" | jobs by tape: ").append(jobsByTapeCount);
            sb.append(" | jobs from active tapes: ").append(activeJobsByTapeCount);
            sb.append(" | immediate jobs: ").append(immediateJobsCount);
            sb.append(" | SUM: ").append(overallCount);
            sb.append("\n\n");

            if (!tapesWithJobs.isEmpty()) {
                sb.append("Tape | Job count\n");
                tapesWithJobs.entrySet().stream().forEach( e -> sb.append(e.getKey()).append(" | ").append(e.getValue().size()).append("\n") );
            }
        }
        return sb.toString();
    }

}
