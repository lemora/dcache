package dmg.cells.nucleus.protocols;
import com.google.common.io.ByteStreams;
import dmg.cells.nucleus.CellMessage;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.*;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1)
@Warmup(iterations = 4, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 8, time = 5, timeUnit = TimeUnit.SECONDS)
@State(Scope.Benchmark)
public class ProtoCellMsgBenchmarker {

    @State(Scope.Thread)
    public static class CellMesssageWithEncodedPayload {

        public CellMessage cm;
        public DataOutputStream outStream;

        @Setup(Level.Invocation)
        public void doSetup() throws IOException {
            cm = new CellMessage();
            cm.setMessageObject("Hello dCache");
            cm = cm.encode();

            ByteArrayOutputStream out = new ByteArrayOutputStream(256);
            outStream = new DataOutputStream(out);
        }
    }

    @State(Scope.Thread)
    public static class CellMesssageStream_proto {

        public DataInputStream inStream;

        @Setup(Level.Invocation)
        public void doSetup() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutput outStream = new DataOutputStream(out);
            CellMessage cm = new CellMessage();
            cm.setMessageObject("Hello dCache");
            cm = cm.encode();
            cm.writeProtoEncodedTo(outStream);

            inStream = new DataInputStream(new ByteArrayInputStream(out.toByteArray()));
        }
    }

    @State(Scope.Thread)
    public static class CellMesssageStream_jos {

        public DataInputStream inStream;

        @Setup(Level.Invocation)
        public void doSetup() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutput outStream = new DataOutputStream(out);
            CellMessage cm = new CellMessage();
            cm.setMessageObject("Hello dCache");
            cm = cm.encode();
            cm.writeTo(outStream);

            inStream = new DataInputStream(new ByteArrayInputStream(out.toByteArray()));
        }
    }

// ------ Protobuf ------

    @Benchmark
    public int serializeCellMessage_proto(CellMesssageWithEncodedPayload cellMsg) throws IOException {
        cellMsg.cm.writeProtoEncodedTo(cellMsg.outStream);
        return cellMsg.outStream.size();
    }

    @Benchmark
    public CellMessage deserializeCellMessage_proto(CellMesssageStream_proto cms) throws IOException {
        return CellMessage.createFrom(cms.inStream);
    }

    // ------ JOS ------

    @Benchmark
    public int serializeCellMessage_jos(CellMesssageWithEncodedPayload cellMsg) throws IOException {
        cellMsg.cm.writeTo(cellMsg.outStream);
        return cellMsg.outStream.size();
    }

    @Benchmark
    public CellMessage deserializeCellMessage_jos(CellMesssageStream_jos cms) throws IOException {
        return CellMessage.createFrom(cms.inStream);
    }
}
