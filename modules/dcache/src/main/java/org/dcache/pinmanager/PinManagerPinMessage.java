package org.dcache.pinmanager;

import static java.util.Objects.requireNonNull;
import static org.dcache.namespace.FileAttribute.PNFSID;

import diskCacheV111.util.PnfsId;
import diskCacheV111.vehicles.Message;
import diskCacheV111.vehicles.PoolMgrSelectReadPoolMsg;
import diskCacheV111.vehicles.ProtocolInfo;
import java.io.ObjectStreamException;
import java.util.Date;
import java.util.EnumSet;
import java.util.Optional;
import jline.internal.Nullable;
import org.dcache.auth.attributes.Restriction;
import org.dcache.auth.attributes.Restrictions;
import org.dcache.namespace.FileAttribute;
import org.dcache.pinmanager.model.Pin;
import org.dcache.vehicles.FileAttributes;

public class PinManagerPinMessage extends Message {

    private static final long serialVersionUID = -146552359952271936L;

    private FileAttributes _fileAttributes;
    private Restriction _restriction;
    private final ProtocolInfo _protocolInfo;
    private long _lifetime;
    private long _pinId;
    private String _pool;
    private final String _requestId;
    private Date _expirationTime;
    private boolean _denyStaging;
    private boolean _pinningInProgress;

    public PinManagerPinMessage(FileAttributes fileAttributes,
          ProtocolInfo protocolInfo,
          Restriction restriction,
          String requestId,
          long lifetime) {
        _fileAttributes = requireNonNull(fileAttributes);
        _protocolInfo = requireNonNull(protocolInfo);
        _restriction = (restriction == null) ? Restrictions.none() : restriction;
        _requestId = requestId;
        _lifetime = lifetime;
    }

    public PinManagerPinMessage(FileAttributes fileAttributes,
          ProtocolInfo protocolInfo,
          String requestId,
          long lifetime) {
        this(fileAttributes, protocolInfo, Restrictions.none(), requestId, lifetime);
    }

    public void setDenyStaging(boolean value) {
        _denyStaging = value;
    }

    public boolean isStagingDenied() {
        return _denyStaging;
    }

    public String getRequestId() {
        return _requestId;
    }

    public void setLifetime(long lifetime) {
        _lifetime = lifetime;
    }

    public long getLifetime() {
        return _lifetime;
    }

    public PnfsId getPnfsId() {
        return _fileAttributes.getPnfsId();
    }

    public FileAttributes getFileAttributes() {
        return _fileAttributes;
    }

    public void setFileAttributes(FileAttributes attributes) {
        _fileAttributes = requireNonNull(attributes);
    }

    public ProtocolInfo getProtocolInfo() {
        return _protocolInfo;
    }

    public Restriction getRestriction() {
        return _restriction;
    }

    @Nullable
    public String getPool() {
        return _pool;
    }

    public void setPool(String pool) {
        _pool = pool;
    }

    public Optional<Long> getPinId() {
        return _pinId == 0 ? Optional.empty() : Optional.of(_pinId);
    }

    public void setPinId(long pinId) {
        _pinId = pinId;
    }

    public void setPinningInProgress(boolean pinningInProgress) {
        _pinningInProgress = pinningInProgress;
    }

    public boolean isPinningInProgress() {
        return _pinningInProgress;
    }

    public void setExpirationTime(Date expirationTime) {
        _expirationTime = expirationTime;
    }

    public Date getExpirationTime() {
        return _expirationTime;
    }

    public void setPin(Pin pin) {
        _pinningInProgress = false;
        setPool(pin.getPool());
        setPinId(pin.getPinId());
        setExpirationTime(pin.getExpirationTime());
    }

    @Override
    public String toString() {
        return "PinManagerPinMessage[" + _fileAttributes + "," + _protocolInfo + "," + _lifetime
              + "]";
    }

    public static EnumSet<FileAttribute> getRequiredAttributes() {
        EnumSet<FileAttribute> attributes = EnumSet.of(PNFSID);
        attributes.addAll(PoolMgrSelectReadPoolMsg.getRequiredAttributes());
        return attributes;
    }

    private Object readResolve() throws ObjectStreamException {
        if (_restriction == null) {
            _restriction = Restrictions.none();
        }
        return this;
    }

}
