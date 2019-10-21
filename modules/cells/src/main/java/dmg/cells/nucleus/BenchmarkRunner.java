package dmg.cells.nucleus;

import dmg.cells.nucleus.protocols.ProtoCellMsgBenchmarker;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.text.SimpleDateFormat;
import java.util.Calendar;

public class BenchmarkRunner {

    public static void main(String[] args) throws RunnerException {

        String simpleClassName = ProtoCellMsgBenchmarker.class.getSimpleName();
        String fileName = "benchmark";
        String pathToResults = "jmh/";

        Options opt = new OptionsBuilder()
                .include(simpleClassName)
//                .resultFormat(ResultFormatType.JSON)
                .result(pathToResults + fileName + ".json")
//                .output(pathToResults + "-" + fileName + ".log")
                .threads(1)
                .build();

        new Runner(opt).run();
    }

}
