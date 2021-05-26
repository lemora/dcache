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

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.dcache.trs.spi.TapeInfoProvider;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.modules.junit4.PowerMockRunner;

@RunWith(PowerMockRunner.class)
@PowerMockIgnore({"com.sun.org.apache.xerces.*",
      "javax.xml.*", "org.xml.*", "org.w3c.*", "jdk.xml.*"})
public class SchedulingStrategyTests {

    /**
     * time safety margin in milliseconds
     */
    private static final long TIME_SAFETY_MARGIN = 20;

    SchedulingStrategy strategy;
    TrsRequirementsChecker requirementsChecker;
    LocalTapeInfoProvider tapeInfoProvider;

    class LocalTapeInfoProvider implements TapeInfoProvider {

        private HashMap<String, TapeInfo> tapeInfo = new HashMap<>();
        private HashMap<String, FileInfo> tapeFileInfo = new HashMap<>();

        public void addTapeInfo(String tape, TapeInfo tapeInfo) {
            this.tapeInfo.put(tape, tapeInfo);
        }

        public void addTapeFileInfo(String file, FileInfo tapeFileInfo) {
            this.tapeFileInfo.put(file, tapeFileInfo);
        }

        @Override
        public Map<String, TapeInfo> getTapeInfos(List<String> tapes) {
            return tapeInfo.entrySet().stream()
                  .filter(e -> tapes.contains(e.getKey()))
                  .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
        }

        @Override
        public Map<String, FileInfo> getTapefileInfos(List<String> fileids) {
            return tapeFileInfo.entrySet()
                  .stream()
                  .filter(e -> fileids.contains(e.getKey()))
                  .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
        }

        @Override
        public String describe() {
            return null;
        }

        @Override
        public boolean reload() {
            return false;
        }

    }

    private TrsJob createJob(String jobid, String pnfsid, long ctime) {
        TrsJob job = new TrsJob(jobid, pnfsid, ctime);
        return job;
    }

    private long getNewCtime() {
        return System.currentTimeMillis() - TIME_SAFETY_MARGIN;
    }

    private long getExpiredCtime() {
        long age =
              requirementsChecker.maxJobWaitingTime() + SECONDS.toMillis(1) + TIME_SAFETY_MARGIN;
        return System.currentTimeMillis() - age;
    }

    private long getCooldownedCtime() {
        long age = requirementsChecker.minJobWaitingTime() + TIME_SAFETY_MARGIN;
        return System.currentTimeMillis() - age;
    }

    @Before
    public void setup() {
        strategy = new SchedulingStrategy();

        requirementsChecker = new TrsRequirementsChecker();
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
        TapeInformant tapeInformant = new TapeInformant();
        tapeInformant.setTapeInfoProvider(tapeInfoProvider);
        strategy.setTapeInformant(tapeInformant);
    }

    @Test
    public void shouldReturnNullWhenQueueIsEmpty() {
        requirementsChecker.setMinJobWaitingTime(Duration.ofMinutes(0));

        assertEquals(0, strategy.size());
        assertNull(strategy.remove());
    }

    @Test
    public void shouldReturnNullWhenOnlyUnexpiredNewJobsExist() {
        requirementsChecker.setMinJobWaitingTime(Duration.ofMinutes(2));

        tapeInfoProvider.addTapeFileInfo("/tape/file10.txt", new FileInfo(10, "tape1"));

        assertEquals(0, strategy.size());
        strategy.add(createJob("10", "/tape/file10.txt", getNewCtime()));

        assertEquals(1, strategy.size());
        assertNull(strategy.remove());
        assertEquals(1, strategy.size());
    }

    @Test
    public void shouldAddAndRemoveSingleTapeJobCorrectly() {
        tapeInfoProvider.addTapeFileInfo("/tape/file10.txt", new FileInfo(10, "tape1"));

        assertEquals(0, strategy.size());
        strategy.add(createJob("10", "/tape/file10.txt", getNewCtime()));
        assertEquals(1, strategy.size());

        assertEquals("10:/tape/file10.txt", strategy.remove());
        assertEquals(0, strategy.size());
    }

    @Test
    public void shouldReturnJobsByLongestTapeQueue() {
        tapeInfoProvider.addTapeFileInfo("/tape/file10.txt", new FileInfo(10, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file20.txt", new FileInfo(10, "tape2"));
        tapeInfoProvider.addTapeFileInfo("/tape/file21.txt", new FileInfo(10, "tape2"));

        assertEquals(0, strategy.size());
        long t0 = getNewCtime();
        strategy.add(createJob("21", "/tape/file21.txt", t0 - 2 * TIME_SAFETY_MARGIN));
        strategy.add(createJob("10", "/tape/file10.txt", t0 - 1 * TIME_SAFETY_MARGIN));
        strategy.add(createJob("20", "/tape/file20.txt", t0 - 0));
        assertEquals(3, strategy.size());

        assertEquals("21:/tape/file21.txt", strategy.remove());
        assertEquals("20:/tape/file20.txt", strategy.remove());
        assertEquals("10:/tape/file10.txt", strategy.remove());

        assertEquals(0, strategy.size());
    }

    @Test
    public void shouldReturnJobsByLongestTwoTapeQueuesInParallel() {
        requirementsChecker.setMaxActiveTapes(2);

        tapeInfoProvider.addTapeFileInfo("/tape/file10.txt", new FileInfo(10, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file11.txt", new FileInfo(10, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file12.txt", new FileInfo(10, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file20.txt", new FileInfo(10, "tape2"));
        tapeInfoProvider.addTapeFileInfo("/tape/file30.txt", new FileInfo(10, "tape3"));
        tapeInfoProvider.addTapeFileInfo("/tape/file31.txt", new FileInfo(10, "tape3"));

        assertEquals(0, strategy.size());
        long t0 = getNewCtime();
        strategy.add(createJob("10", "/tape/file10.txt", t0 - 5 * TIME_SAFETY_MARGIN));
        strategy.add(createJob("12", "/tape/file12.txt", t0 - 4 * TIME_SAFETY_MARGIN));
        strategy.add(createJob("11", "/tape/file11.txt", t0 - 3 * TIME_SAFETY_MARGIN));
        strategy.add(createJob("20", "/tape/file20.txt", t0 - 2 * TIME_SAFETY_MARGIN));
        strategy.add(createJob("31", "/tape/file31.txt", t0 - 1 * TIME_SAFETY_MARGIN));
        strategy.add(createJob("30", "/tape/file30.txt", t0 - 0));
        assertEquals(6, strategy.size());

        /* queues (within tape by order of arrival):
        tape1: 10, 12, 11
        tape2: 20
        tape3: 31, 30
        strategy returns two jobs "at a time" (2 active tapes) sorted by ctime
         */
        assertEquals("10:/tape/file10.txt", strategy.remove());
        assertEquals("31:/tape/file31.txt", strategy.remove());

        assertEquals("12:/tape/file12.txt", strategy.remove());
        assertEquals("30:/tape/file30.txt", strategy.remove());

        assertEquals("11:/tape/file11.txt", strategy.remove());
        assertEquals("20:/tape/file20.txt", strategy.remove());

        assertEquals(0, strategy.size());
    }

    @Test
    public void shouldReturnJobsByLongestThreeTapeQueuesInParallel() {
        requirementsChecker.setMaxActiveTapes(3);

        tapeInfoProvider.addTapeFileInfo("/tape/file10.txt", new FileInfo(10, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file11.txt", new FileInfo(10, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file12.txt", new FileInfo(10, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file20.txt", new FileInfo(10, "tape2"));
        tapeInfoProvider.addTapeFileInfo("/tape/file30.txt", new FileInfo(10, "tape3"));
        tapeInfoProvider.addTapeFileInfo("/tape/file31.txt", new FileInfo(10, "tape3"));

        assertEquals(0, strategy.size());
        long t0 = getNewCtime();
        strategy.add(createJob("10", "/tape/file10.txt", t0 - 5 * TIME_SAFETY_MARGIN));
        strategy.add(createJob("12", "/tape/file12.txt", t0 - 4 * TIME_SAFETY_MARGIN));
        strategy.add(createJob("11", "/tape/file11.txt", t0 - 3 * TIME_SAFETY_MARGIN));
        strategy.add(createJob("20", "/tape/file20.txt", t0 - 2 * TIME_SAFETY_MARGIN));
        strategy.add(createJob("31", "/tape/file31.txt", t0 - 1 * TIME_SAFETY_MARGIN));
        strategy.add(createJob("30", "/tape/file30.txt", t0 - 0));
        assertEquals(6, strategy.size());

        assertEquals("10:/tape/file10.txt", strategy.remove());
        assertEquals("20:/tape/file20.txt", strategy.remove());
        assertEquals("31:/tape/file31.txt", strategy.remove());

        assertEquals("12:/tape/file12.txt", strategy.remove());
        assertEquals("30:/tape/file30.txt", strategy.remove());

        assertEquals("11:/tape/file11.txt", strategy.remove());

        assertEquals(0, strategy.size());
    }

    @Test
    public void shouldReturnOnlyExpiredNewJob() {
        requirementsChecker.setMinNumberOfRequestsForTapeSelection(100);
        requirementsChecker.setMinJobWaitingTime(Duration.ofMinutes(2));

        assertEquals(0, strategy.size());
        strategy.add(createJob("10", "/tape/file10.txt", getNewCtime()));
        strategy.add(createJob("11", "/tape/file11.txt", getExpiredCtime()));
        assertEquals(2, strategy.size());

        assertEquals("11:/tape/file11.txt", strategy.remove());
        assertNull(strategy.remove());
        assertEquals(1, strategy.size());
    }

    @Test
    public void shouldSelectTapesWithExpiredJobsAndOldestExpiryFirst() {
        tapeInfoProvider.addTapeFileInfo("/tape/file10.txt", new FileInfo(10, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file11.txt", new FileInfo(10, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file12.txt", new FileInfo(10, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file20.txt", new FileInfo(10, "tape2"));
        tapeInfoProvider.addTapeFileInfo("/tape/file30.txt", new FileInfo(10, "tape3"));
        tapeInfoProvider.addTapeFileInfo("/tape/file31.txt", new FileInfo(10, "tape3"));

        assertEquals(0, strategy.size());
        long t0 = getNewCtime();
        long expired_t0 = getExpiredCtime();
        strategy.add(createJob("10", "/tape/file10.txt", t0 - 3 * TIME_SAFETY_MARGIN));
        strategy.add(createJob("12", "/tape/file12.txt", t0 - 2 * TIME_SAFETY_MARGIN));
        strategy.add(createJob("11", "/tape/file11.txt", t0 - 1 * TIME_SAFETY_MARGIN));
        strategy.add(createJob("20", "/tape/file20.txt", expired_t0 - TIME_SAFETY_MARGIN));
        strategy.add(createJob("31", "/tape/file31.txt", expired_t0 - 0));
        strategy.add(createJob("30", "/tape/file30.txt", t0 - 0));
        assertEquals(6, strategy.size());

        assertEquals("20:/tape/file20.txt", strategy.remove());
        assertEquals("31:/tape/file31.txt", strategy.remove());
        assertEquals("30:/tape/file30.txt", strategy.remove());
        assertEquals("10:/tape/file10.txt", strategy.remove());
        assertEquals("12:/tape/file12.txt", strategy.remove());
        assertEquals("11:/tape/file11.txt", strategy.remove());

        assertEquals(0, strategy.size());
    }

    @Test
    public void shouldOnlySelectTapesFromWhichEnoughVolumeIsRecalledByRecallVolume() {
        requirementsChecker.setMinNumberOfRequestsForTapeSelection(1000);
        requirementsChecker.setMinTapeRecallPercentage(40);

        tapeInfoProvider.addTapeFileInfo("/tape/file10.txt", new FileInfo(2, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file11.txt", new FileInfo(2, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file12.txt", new FileInfo(2, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file20.txt", new FileInfo(100, "tape2"));
        tapeInfoProvider.addTapeFileInfo("/tape/file30.txt", new FileInfo(50, "tape3"));
        tapeInfoProvider.addTapeFileInfo("/tape/file31.txt", new FileInfo(18, "tape3"));
        // tape1 40/100, we recall 6 (not neough)
        // tape2 100/100, we recall 100 (enough)
        // tape3 70/100, we recall 68 (enough)

        assertEquals(0, strategy.size());
        long t0 = getNewCtime();
        strategy.add(createJob("10", "/tape/file10.txt", t0 - 5 * TIME_SAFETY_MARGIN));
        strategy.add(createJob("12", "/tape/file12.txt", t0 - 4 * TIME_SAFETY_MARGIN));
        strategy.add(createJob("11", "/tape/file11.txt", t0 - 3 * TIME_SAFETY_MARGIN));
        strategy.add(createJob("20", "/tape/file20.txt", t0 - 2 * TIME_SAFETY_MARGIN));
        strategy.add(createJob("31", "/tape/file31.txt", t0 - 1 * TIME_SAFETY_MARGIN));
        strategy.add(createJob("30", "/tape/file30.txt", t0 - 0));
        assertEquals(6, strategy.size());

        assertEquals("20:/tape/file20.txt", strategy.remove());
        assertEquals("31:/tape/file31.txt", strategy.remove());
        assertEquals("30:/tape/file30.txt", strategy.remove());
        assertNull(strategy.remove());

        assertEquals(3, strategy.size());
    }

    @Test
    public void shouldOnlySelectTapesWithCooldownSinceLastJobArrival() {
        requirementsChecker.setMinJobWaitingTime(Duration.ofMinutes(15));

        tapeInfoProvider.addTapeFileInfo("/tape/file10.txt", new FileInfo(10, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file11.txt", new FileInfo(10, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file12.txt", new FileInfo(10, "tape1"));
        tapeInfoProvider.addTapeFileInfo("/tape/file20.txt", new FileInfo(10, "tape2"));
        tapeInfoProvider.addTapeFileInfo("/tape/file30.txt", new FileInfo(10, "tape3"));
        tapeInfoProvider.addTapeFileInfo("/tape/file31.txt", new FileInfo(10, "tape3"));

        assertEquals(0, strategy.size());
        long t0 = getNewCtime();
        long cooldowned_t0 = getCooldownedCtime();
        strategy.add(createJob("10", "/tape/file10.txt", t0 - 3 * TIME_SAFETY_MARGIN));
        strategy.add(createJob("12", "/tape/file12.txt", t0 - 2 * TIME_SAFETY_MARGIN));
        strategy.add(createJob("11", "/tape/file11.txt", t0 - 1 * TIME_SAFETY_MARGIN));
        strategy.add(createJob("20", "/tape/file20.txt", t0 - 0));
        strategy.add(createJob("31", "/tape/file31.txt", cooldowned_t0 - TIME_SAFETY_MARGIN));
        strategy.add(createJob("30", "/tape/file30.txt", cooldowned_t0 - 0));
        assertEquals(6, strategy.size());

        assertEquals("31:/tape/file31.txt", strategy.remove());
        assertEquals("30:/tape/file30.txt", strategy.remove());
        assertNull(strategy.remove());

        assertEquals(4, strategy.size());
    }
}
