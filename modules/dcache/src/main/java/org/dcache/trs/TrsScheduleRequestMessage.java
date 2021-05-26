/* dCache - http://www.dcache.org/
 *
 * Copyright (C) 2022 Deutsches Elektronen-Synchrotron
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

import static java.util.Objects.requireNonNull;

import diskCacheV111.util.PnfsId;
import diskCacheV111.vehicles.Message;
import diskCacheV111.vehicles.ProtocolInfo;
import org.dcache.vehicles.FileAttributes;

/**
 * Scheduling and staging is always allowed
 */
public class TrsScheduleRequestMessage extends Message {

    private static final long serialVersionUID = 4486875431478346298L;

    private FileAttributes fileAttributes;
    private long lifetime;
    private final ProtocolInfo protocolInfo;
    private final String requestId; // TODO: generate ID, don't reuse?!

    public TrsScheduleRequestMessage(FileAttributes fileAttributes,
          ProtocolInfo protocolInfo,
          String requestId,
          long lifetime) {
        this.fileAttributes = requireNonNull(fileAttributes);
        this.protocolInfo = requireNonNull(protocolInfo);
        this.requestId = requestId;
        this.lifetime = lifetime;
    }


    public FileAttributes getFileAttributes() {
        return fileAttributes;
    }

    public long getLifetime() {
        return lifetime;
    }

    public PnfsId getPnfsid() {
        return fileAttributes.getPnfsId();
    }

    public ProtocolInfo getProtocolInfo() {
        return protocolInfo;
    }

    public String getRequestId() {
        return requestId;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("TRSScheduleRequestMessage (").append(requestId).append(")");
        sb.append(" for pnfsid: ").append(fileAttributes.getPnfsId());
        return sb.toString();
    }
}
