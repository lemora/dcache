package org.dcache.srm.taperecallscheduling;

import dmg.cells.nucleus.CellInfoProvider;

import java.io.PrintWriter;
import java.time.Duration;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;

public class TapeRecallSchedulingRequirementsChecker implements CellInfoProvider {

    public static final long NO_VALUE = -1;
    private static final long TIME_SAFETY_MARGIN = MILLISECONDS.toMillis(10);
    private static final long MIN_RELATIVE_TAPE_RECALL_PERCENTAGE = 90; // of _used_ space

    // scheduling parameters

    private int maxActiveTapes = 1;
    private int minTapeRecallPercentage = 80;
    private long minNumberOfRequestsForTapeSelection = NO_VALUE;
    private long minJobWaitingTime = MINUTES.toMillis(2);
    private long maxJobWaitingTime = HOURS.toMillis(1);

    public void setMaxActiveTapes(int tapeCount) {
        checkArgument(tapeCount > 0, "There need to be more than 0 max. active tapes");
        this.maxActiveTapes = tapeCount;
    }

    public void setMinJobWaitingTime(Duration time) {
        checkArgument(time.toMillis() >= 0, "The min. job waiting time needs to be 0 or larger");
        minJobWaitingTime = time.toMillis();
    }

    public void setMaxJobWaitingTime(Duration time) {
        checkArgument(time.toMillis() >= 0, "The max. job waiting time needs to be 0 or larger");
        this.maxJobWaitingTime = time.toMillis();
    }

    public void setMinNumberOfRequestsForTapeSelection(long number) {
        checkArgument(number >= 0, "The min. number of requests for tape selection needs to be 0 or larger");
        minNumberOfRequestsForTapeSelection = number;
    }

    public void setMinTapeRecallPercentage(int percentage) {
        checkArgument(percentage >= 0 && percentage <= 100, "The minimum tape recall percentage needs to be between 0 and 100");
        minTapeRecallPercentage = percentage;
    }

    public int maxActiveTapes() {
        return maxActiveTapes;
    }

    public int minTapeRecallPercentage() {
        return minTapeRecallPercentage;
    }

    public long minNumberOfRequestsForTapeSelection() {
        return minNumberOfRequestsForTapeSelection;
    }

    public long minJobWaitingTime() {
        return minJobWaitingTime;
    }

    public long maxJobWaitingTime() {
        return maxJobWaitingTime;
    }

    // ----- abstraction layer for access to methods that require those parameters

    /**
     * If the oldest request for a tape has exceeded its max. queue lifetime.
     * @param tape the scheduling info for the tape in question
     * @return whether the tape's oldest jost is expired
     */
    public boolean isOldestTapeJobExpired(SchedulingInfoTape tape) {
        if (tape.getOldestJobArrival() == NO_VALUE) {
            return false;
        }
        long ageOfOldestJobArrival = System.currentTimeMillis() - tape.getOldestJobArrival();
        long correctedMaxAge = maxJobWaitingTime + TIME_SAFETY_MARGIN;
        return ageOfOldestJobArrival >= correctedMaxAge;
    }

    /**
     * @return if newest job for a tape is old enough for the tape to be selected
     * @param tape the scheduling info for the tape in question
     */
    public boolean isNewestTapeJobOldEnough(SchedulingInfoTape tape) {
        if (tape.getNewestJobArrival() == NO_VALUE) {
            return false;
        } else if (minJobWaitingTime == NO_VALUE) {
            return true;
        }
        long ageOfNewestJobArrival = System.currentTimeMillis() - tape.getNewestJobArrival();
        long correctedMinAge = minJobWaitingTime + TIME_SAFETY_MARGIN;
        return ageOfNewestJobArrival >= correctedMinAge;
    }

    /**
     * Assesses if the provided absolute recall volume exceeds the configured percentage of overall tape capacity.
     * Always returns true if more than 90 percent of the tape's used space is requested, irrespective of the
     * overall percentage.
     * If the tape's capacity and used space is unknown, false is returned.
     *
     * @param tape the scheduling info for the tape in question
     * @param recallVolume Recall volume in kB
     * @return Whether the recall volume is sufficient
     */
    public boolean isTapeRecallVolumeSufficient(SchedulingInfoTape tape, long recallVolume) {
        if (!tape.hasTapeInfo()) {
            return minTapeRecallPercentage == 0;
        }
        boolean recalledMostOfUsedSpace = recallVolume / tape.getUsedSpace() >= MIN_RELATIVE_TAPE_RECALL_PERCENTAGE;
        if (recalledMostOfUsedSpace) {
            return true;
        }
        return recallVolume * 100/ tape.getCapacity() >= minTapeRecallPercentage;
    }

    public int compareOldestTapeRequestAge(SchedulingInfoTape first, SchedulingInfoTape second) {
        long arrivalFirst = first.getOldestJobArrival();
        long arrivalSecond = second.getOldestJobArrival();
        if (arrivalFirst == NO_VALUE && arrivalSecond == NO_VALUE) {
            return 0;
        } else if(arrivalFirst == NO_VALUE && arrivalSecond != NO_VALUE) {
            return -1;
        } else if(arrivalFirst != NO_VALUE && arrivalSecond == NO_VALUE) {
            return 1;
        }
        return Long.compare(arrivalFirst, arrivalSecond);
    }

    public boolean isJobExpired(SchedulingItemJob job) {
        long age = System.currentTimeMillis() - job.getCreationTime();
        long correctedMaxAge = maxJobWaitingTime + TIME_SAFETY_MARGIN;
        return age > correctedMaxAge;
    }


    @Override
    public void getInfo(PrintWriter pw) {
        pw.printf("Bring online scheduler parameters:\n");
        pw.printf("    Max. active tapes: %s\n", maxActiveTapes);
        pw.printf("    Min. recall volume percentage for tape selection: %s\n", minTapeRecallPercentage);
        pw.printf("    Min. number of requests for tape selection: %s\n", minNumberOfRequestsForTapeSelection);
        pw.printf("    Min. age of newest request for tape selection: %s\n", minJobWaitingTime);
        pw.printf("    Max. age of newest request for tape selection: %s\n", maxJobWaitingTime);
    }
}
