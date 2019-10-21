package dmg.cells.nucleus;

import dmg.cells.network.PingMessage;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.Serializable;
import java.util.Arrays;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

public class CellMessageTest
{
//    @Test
//    public void shouldDeserializeSerializedCellMessage_protobuf() throws Exception
//    {
//        CellMessage message = new CellMessage(new CellPath("foo", "bar"), "payload");
//
//        CellMessage encoded = message.encode();
//        byte[] serialized = encoded.createProtoObject().toByteArray();
//        CellMessage deserialized = new CellMessage(serialized);
//        CellMessage decoded = deserialized.decode();
//
//        assertThat(decoded.getUOID(), is(message.getUOID()));
//        assertThat(decoded.getLastUOID(), is(message.getUOID()));
//        assertThat(decoded.getSourcePath(), is(message.getSourcePath()));
//        assertThat(decoded.getDestinationPath(), is(message.getDestinationPath()));
//        assertThat(decoded.getMessageObject(), is((Serializable) "payload"));
//        assertThat(decoded.getSession(), nullValue());
//        assertThat(decoded.getTtl(), is(message.getTtl()));
//    }

    @Test
    public void shouldDeserializeSerializedCellMessageStream_Java() throws Exception
    {
        CellMessage message = new CellMessage(new CellPath("foo", "bar"), "payload");

        CellMessage encoded = message.encode();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream outStream = new DataOutputStream(out);
        encoded.writeTo(outStream);

        DataInputStream inStream = new DataInputStream(new ByteArrayInputStream(out.toByteArray()));
        CellMessage deserialized = CellMessage.createFrom(inStream);

        CellMessage decoded = deserialized.decode();

        assertThat(decoded.getUOID(), is(message.getUOID()));
        assertThat(decoded.getLastUOID(), is(message.getUOID()));
        assertThat(decoded.getSourcePath(), is(message.getSourcePath()));
        assertThat(decoded.getDestinationPath(), is(message.getDestinationPath()));
        assertThat(decoded.getMessageObject(), is((Serializable) "payload"));
        assertThat(decoded.getSession(), nullValue());
        assertThat(decoded.getTtl(), is(message.getTtl()));
    }

    @Test
    public void shouldDeserializeSerializedCellMessageStream_protobuf() throws Exception
    {
        CellMessage message = new CellMessage(new CellPath("foo", "bar"), "payload");

        CellMessage encoded = message.encode();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream outStream = new DataOutputStream(out);
        encoded.writeProtoEncodedTo(outStream);

        DataInputStream inStream = new DataInputStream(new ByteArrayInputStream(out.toByteArray()));
        CellMessage deserialized = CellMessage.createFrom(inStream);

        CellMessage decoded = deserialized.decode();

        assertThat(decoded.getUOID(), is(message.getUOID()));
        assertThat(decoded.getLastUOID(), is(message.getUOID()));
        assertThat(decoded.getSourcePath(), is(message.getSourcePath()));
        assertThat(decoded.getDestinationPath(), is(message.getDestinationPath()));
        assertThat(decoded.getMessageObject(), is((Serializable) "payload"));
        assertThat(decoded.getSession(), nullValue());
        assertThat(decoded.getTtl(), is(message.getTtl()));
    }

    @Test(expected = IllegalStateException.class)
    public void shouldFailToSerializedUnencodedMessage() throws Exception
    {
        CellMessage message = new CellMessage(new CellPath("foo", "bar"), "payload");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream outStream = new DataOutputStream(out);
        message.writeTo(outStream);
    }

}