package org.dcache.taperecallscheduler;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.modules.junit4.PowerMockRunner;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;

@RunWith(PowerMockRunner.class)
@PowerMockIgnore({"com.sun.org.apache.xerces.*",
        "javax.xml.*", "org.xml.*", "org.w3c.*", "jdk.xml.*"})
public class TapeRecallSchedulingStrategyTest {

    private static final long TIME_SAFETY_MARGIN = MILLISECONDS.toMillis(20);

    TapeRecallSchedulingStrategy strategy;
    TapeRecallSchedulingRequirementsChecker requirementsChecker;
    LocalTapeInfoProvider tapeInfoProvider;

    class LocalTapeInfoProvider implements TapeInfoProvider {

        private HashMap<String, TapeInfo> tapeInfo = new HashMap<>();
        private HashMap<String, TapefileInfo> tapeFileInfo = new HashMap<>();

        public void addTapeInfo(String tape, TapeInfo tapeInfo) {
            this.tapeInfo.put(tape, tapeInfo);
        }

        public void addTapeFileInfo(String file, TapefileInfo tapeFileInfo) {
            this.tapeFileInfo.put(file, tapeFileInfo);
        }

        @Override
        public Map<String, TapeInfo> getTapeInfos(List<String> tapes) {
            return tapeInfo.entrySet().stream()
                    .filter(e -> tapes.contains(e.getKey()))
                    .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
        }

        @Override
        public Map<String, TapefileInfo> getTapefileInfos(List<String> fileids) {
            return tapeFileInfo.entrySet()
                    .stream()
                    .filter(e -> fileids.contains(e.getKey()))
                    .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
        }
    }

    private BringOnlineJob createJob(long jobid, String file, long ctime) {
        BringOnlineJob job = new BringOnlineJob(jobid, file, ctime);
        return job;
    }

    private long getNewCtime() {
        return System.currentTimeMillis() - TIME_SAFETY_MARGIN;
    }

    private long getExpiredCtime() {
        long age = requirementsChecker.maxJobWaitingTime() + SECONDS.toMillis(1) + TIME_SAFETY_MARGIN;
        long res = System.currentTimeMillis() - age;
        return res;
    }

    private long getCooldownedCtime() {
        long age = requirementsChecker.minJobWaitingTime() + TIME_SAFETY_MARGIN;
        return System.currentTimeMillis() - age;
    }

    @Before
    public void setup() {
        strategy = new TapeRecallSchedulingStrategy();

        requirementsChecker = new TapeRecallSchedulingRequirementsChecker();
        requirementsChecker.setMaxActiveTapes(1);
        requirementsChecker.setMinTapeRecallPercentage(80);
        requirementsChecker.setMinNumberOfRequestsForTapeSelection(1);
        requirementsChecker.setMinJobWaitingTime(Duration.ofMinutes(0));
        requirementsChecker.setMaxJobWaitingTime(Duration.ofHours(1));

        tapeInfoProvider = new LocalTapeInfoProvider();
        tapeInfoProvider.addTapeInfo("tape1", new TapeInfo(100, 40));
        tapeInfoProvider.addTapeInfo("tape2", new TapeInfo(100, 100));
        tapeInfoProvider.addTapeInfo("tape3", new TapeInfo(100, 70));

        strategy.setRequirementsChecker(requirementsChecker);
        strategy.setTapeInfoProvider(tapeInfoProvider);
    }

    @Test
    public void shouldReturnNullWhenQueueIsEmpty() {
        requirementsChecker.setMinJobWaitingTime(Duration.ofMinutes(0));

        assertEquals(0, strategy.size());
        assertEquals(null, strategy.remove());
    }

    @Test
    public void shouldReturnNullWhenOnlyUnexpiredNewJobsExist() {
        requirementsChecker.setMinJobWaitingTime(Duration.ofMinutes(2));
        tapeInfoProvider.addTapeFileInfo("/tape/file10.txt", new TapefileInfo(10, "tape1"));

        assertEquals(0, strategy.size());
        strategy.add( createJob(10, "/tape/file10.txt", getNewCtime()) );

        assertEquals(1, strategy.size());
        assertEquals(null, strategy.remove());
        assertEquals(1, strategy.size());
    }

    @Test
    public void shouldAddAndRemoveSingleTapeJobCorrectly() {
        tapeInfoProvider.addTapeFileInfo("/tape/file10.txt", new TapefileInfo(10, "tape1"));

        assertEquals(0, strategy.size());
        strategy.add( createJob(10, "/tape/file10.txt", getNewCtime()) );
        assertEquals(1, strategy.size());

        assertEquals(Long.valueOf(10), strategy.remove());
        assertEquals(0, strategy.size());
    }

    @Test
    public void shouldReturnJobsByLongestTapeQueue() {
        tapeInfoProvider.addTapeFileInfo("/tape/file10.txt", new TapefileInfo(10, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file20.txt", new TapefileInfo(10, "tape2"));
        tapeInfoProvider.addTapeFileInfo("/tape/file21.txt", new TapefileInfo(10, "tape2"));

        assertEquals(0, strategy.size());
        long ctime = getNewCtime();
        strategy.add( createJob(21, "/tape/file21.txt", ctime - 2) );
        strategy.add( createJob(10, "/tape/file10.txt", ctime - 1) );
        strategy.add( createJob(20, "/tape/file20.txt", ctime - 0) );
        assertEquals(3, strategy.size());

        assertEquals(Long.valueOf(21), strategy.remove());
        assertEquals(Long.valueOf(20), strategy.remove());
        assertEquals(Long.valueOf(10), strategy.remove());

        assertEquals(0, strategy.size());
    }

    @Test
    public void shouldReturnJobsByLongestTwoTapeQueuesInParallel() {
        requirementsChecker.setMaxActiveTapes(2);
        tapeInfoProvider.addTapeFileInfo("/tape/file10.txt", new TapefileInfo(10, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file11.txt", new TapefileInfo(10, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file12.txt", new TapefileInfo(10, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file20.txt", new TapefileInfo(10, "tape2"));
        tapeInfoProvider.addTapeFileInfo("/tape/file30.txt", new TapefileInfo(10, "tape3"));
        tapeInfoProvider.addTapeFileInfo("/tape/file31.txt", new TapefileInfo(10, "tape3"));

        assertEquals(0, strategy.size());
        long ctime = getNewCtime();
        strategy.add( createJob(10, "/tape/file10.txt", ctime - 5) );
        strategy.add( createJob(12, "/tape/file12.txt", ctime - 4) );
        strategy.add( createJob(11, "/tape/file11.txt", ctime - 3) );
        strategy.add( createJob(20, "/tape/file20.txt", ctime - 2) );
        strategy.add( createJob(31, "/tape/file31.txt", ctime - 1) );
        strategy.add( createJob(30, "/tape/file30.txt", ctime - 0) );
        assertEquals(6, strategy.size());

        assertEquals(Long.valueOf(10), strategy.remove());
        assertEquals(Long.valueOf(31), strategy.remove());

        assertEquals(Long.valueOf(12), strategy.remove());
        assertEquals(Long.valueOf(30), strategy.remove());

        assertEquals(Long.valueOf(11), strategy.remove());
        assertEquals(Long.valueOf(20), strategy.remove());

        assertEquals(0, strategy.size());
    }

    @Test
    public void shouldReturnJobsByLongestThreeTapeQueuesInParallel() {
        requirementsChecker.setMaxActiveTapes(3);
        tapeInfoProvider.addTapeFileInfo("/tape/file10.txt", new TapefileInfo(10, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file11.txt", new TapefileInfo(10, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file12.txt", new TapefileInfo(10, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file20.txt", new TapefileInfo(10, "tape2"));
        tapeInfoProvider.addTapeFileInfo("/tape/file30.txt", new TapefileInfo(10, "tape3"));
        tapeInfoProvider.addTapeFileInfo("/tape/file31.txt", new TapefileInfo(10, "tape3"));

        assertEquals(0, strategy.size());
        long ctime = getNewCtime();
        strategy.add( createJob(10, "/tape/file10.txt", ctime - 5) );
        strategy.add( createJob(12, "/tape/file12.txt", ctime - 4) );
        strategy.add( createJob(11, "/tape/file11.txt", ctime - 3) );
        strategy.add( createJob(20, "/tape/file20.txt", ctime - 2) );
        strategy.add( createJob(31, "/tape/file31.txt", ctime - 1) );
        strategy.add( createJob(30, "/tape/file30.txt", ctime - 0) );
        assertEquals(6, strategy.size());

        assertEquals(Long.valueOf(10), strategy.remove());
        assertEquals(Long.valueOf(20), strategy.remove());
        assertEquals(Long.valueOf(31), strategy.remove());

        assertEquals(Long.valueOf(12), strategy.remove());
        assertEquals(Long.valueOf(30), strategy.remove());

        assertEquals(Long.valueOf(11), strategy.remove());

        assertEquals(0, strategy.size());
    }

    @Test
    public void shouldReturnOnlyExpiredNewJob() {
        requirementsChecker.setMinNumberOfRequestsForTapeSelection(100);
        requirementsChecker.setMinJobWaitingTime(Duration.ofMinutes(2));

        assertEquals(0, strategy.size());
        strategy.add(createJob(10, "/tape/file10.txt", getNewCtime()));
        strategy.add(createJob(11, "/tape/file11.txt", getExpiredCtime()));
        assertEquals(2, strategy.size());

        assertEquals(Long.valueOf(11), strategy.remove());
        assertEquals(null, strategy.remove());
        assertEquals(1, strategy.size());
    }

    @Test
    public void shouldSelectTapesWithExpiredJobsAndOldestExpiryFirst() {
        tapeInfoProvider.addTapeFileInfo("/tape/file10.txt", new TapefileInfo(10, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file11.txt", new TapefileInfo(10, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file12.txt", new TapefileInfo(10, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file20.txt", new TapefileInfo(10, "tape2"));
        tapeInfoProvider.addTapeFileInfo("/tape/file30.txt", new TapefileInfo(10, "tape3"));
        tapeInfoProvider.addTapeFileInfo("/tape/file31.txt", new TapefileInfo(10, "tape3"));

        assertEquals(0, strategy.size());
        long ctime = getNewCtime();
        strategy.add( createJob(10, "/tape/file10.txt", ctime - 3) );
        strategy.add( createJob(12, "/tape/file12.txt", ctime - 2) );
        strategy.add( createJob(11, "/tape/file11.txt", ctime - 1) );
        strategy.add( createJob(20, "/tape/file20.txt", getExpiredCtime() - 1) );
        strategy.add( createJob(31, "/tape/file31.txt", getExpiredCtime() - 0) );
        strategy.add( createJob(30, "/tape/file30.txt", ctime - 0) );
        assertEquals(6, strategy.size());

        assertEquals(Long.valueOf(20), strategy.remove());
        assertEquals(Long.valueOf(31), strategy.remove());
        assertEquals(Long.valueOf(30), strategy.remove());
        assertEquals(Long.valueOf(10), strategy.remove());
        assertEquals(Long.valueOf(12), strategy.remove());
        assertEquals(Long.valueOf(11), strategy.remove());

        assertEquals(0, strategy.size());
    }

    @Test
    public void shouldOnlySelectTapesFromWhichEnoughVolumeIsRecalledByRecallVolume() {
        requirementsChecker.setMinNumberOfRequestsForTapeSelection(1000);
        requirementsChecker.setMinTapeRecallPercentage(40);
        tapeInfoProvider.addTapeFileInfo("/tape/file10.txt", new TapefileInfo(2, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file11.txt", new TapefileInfo(2, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file12.txt", new TapefileInfo(2, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file20.txt", new TapefileInfo(100, "tape2"));
        tapeInfoProvider.addTapeFileInfo("/tape/file30.txt", new TapefileInfo(50, "tape3"));
        tapeInfoProvider.addTapeFileInfo("/tape/file31.txt", new TapefileInfo(10, "tape3"));
        // tape1 40/100, we recall 6
        // tape2 100/100, we recall 100 (enough)
        // tape3 70/100, we recall 60 (enough)

        assertEquals(0, strategy.size());
        long ctime = getNewCtime();
        strategy.add( createJob(10, "/tape/file10.txt", ctime  - 5) );
        strategy.add( createJob(12, "/tape/file12.txt", ctime  - 4) );
        strategy.add( createJob(11, "/tape/file11.txt", ctime  - 3) );
        strategy.add( createJob(20, "/tape/file20.txt", ctime  - 2) );
        strategy.add( createJob(31, "/tape/file31.txt", ctime  - 1) );
        strategy.add( createJob(30, "/tape/file30.txt", ctime  - 0) );
        assertEquals(6, strategy.size());

        assertEquals(Long.valueOf(20), strategy.remove());
        assertEquals(Long.valueOf(31), strategy.remove());
        assertEquals(Long.valueOf(30), strategy.remove());
        assertEquals(null, strategy.remove());

        assertEquals(3, strategy.size());
    }

    @Test
    public void shouldOnlySelectTapesWithCooldownSinceLastJobArrival() {
        requirementsChecker.setMinJobWaitingTime(Duration.ofMinutes(2));
        tapeInfoProvider.addTapeFileInfo("/tape/file10.txt", new TapefileInfo(10, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file11.txt", new TapefileInfo(10, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file12.txt", new TapefileInfo(10, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file20.txt", new TapefileInfo(10, "tape2"));
        tapeInfoProvider.addTapeFileInfo("/tape/file30.txt", new TapefileInfo(10, "tape3"));
        tapeInfoProvider.addTapeFileInfo("/tape/file31.txt", new TapefileInfo(10, "tape3"));

        assertEquals(0, strategy.size());
        long ctime = getNewCtime();
        strategy.add( createJob(10, "/tape/file10.txt", ctime  - 3) );
        strategy.add( createJob(12, "/tape/file12.txt", ctime  - 2) );
        strategy.add( createJob(11, "/tape/file11.txt", ctime  - 1) );
        strategy.add( createJob(20, "/tape/file20.txt", ctime  - 0) );
        strategy.add( createJob(31, "/tape/file31.txt", getCooldownedCtime() - 1) );
        strategy.add( createJob(30, "/tape/file30.txt", getCooldownedCtime() - 0) );
        assertEquals(6, strategy.size());

        assertEquals(Long.valueOf(31), strategy.remove());
        assertEquals(Long.valueOf(30), strategy.remove());
        assertEquals(null, strategy.remove());

        assertEquals(4, strategy.size());
    }

}
