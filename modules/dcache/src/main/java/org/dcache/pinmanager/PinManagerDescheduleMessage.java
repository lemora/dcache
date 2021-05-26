package org.dcache.pinmanager;

import diskCacheV111.vehicles.ProtocolInfo;
import org.dcache.vehicles.FileAttributes;

public class PinManagerDescheduleMessage extends PinManagerPinMessage {

    private static final long serialVersionUID = -146552359952271936L; // TODO

    public PinManagerDescheduleMessage(FileAttributes fileAttributes, ProtocolInfo protocolInfo,
          String requestId, long lifetime) {
        super(fileAttributes, protocolInfo, requestId, lifetime);
    }

    @Override
    public String toString() {
        return "PinManagerDescheduleMessage[" + super.getFileAttributes() + ","
              + super.getProtocolInfo() + "," + super.getLifetime() + "]";
    }

}
