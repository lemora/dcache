package org.dcache.taperecallscheduler;

import dmg.cells.nucleus.CellInfoProvider;
import dmg.cells.nucleus.CellLifeCycleAware;
import dmg.cells.nucleus.CellMessageReceiver;

import java.io.PrintWriter;
import java.util.Arrays;

/**
 * Cell core that accepts tape recall requests and returns them by means of callbacks
 */
public class TapeRecallScheduler implements CellLifeCycleAware, CellMessageReceiver, CellInfoProvider {

    TapeRecallSchedulingStrategy tapeRecallStrategy;

    public void setTapeRecallStrategy(TapeRecallSchedulingStrategy strategy) {
        tapeRecallStrategy = strategy;
    }

//    public

    @Override
    public void getInfo(PrintWriter pw) {
        pw.println("Tape recall scheduler information to come.......");
    }

}
