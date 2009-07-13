package org.dcache.util;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import java.io.PrintWriter;
import java.net.InetAddress;

import org.dcache.cells.AbstractCellComponent;
import org.dcache.cells.CellCommandListener;
import dmg.cells.services.login.LoginBrokerInfo;
import dmg.cells.nucleus.CellMessage;
import dmg.cells.nucleus.CellPath;
import dmg.cells.nucleus.NoRouteToCellException;
import dmg.util.Args;

import org.apache.log4j.Logger;

public class LoginBrokerHandler
    extends AbstractCellComponent
    implements CellCommandListener
{
    private final static Logger _log =
        Logger.getLogger(LoginBrokerHandler.class);

    private CellPath _loginBroker;
    private String _protocolFamily;
    private String _protocolVersion;
    private String _protocolEngine;
    private long   _brokerUpdateTime = 5 * 60;
    private double _brokerUpdateThreshold = 0.1;
    private double _currentLoad = 0.0;
    private String[] _hosts = new String[0];
    private int _port;
    private ScheduledExecutorService _executor;
    private ScheduledFuture _task;

    private LoginBrokerHandler()
    {
    }

    public final static String hh_lb_set_update = "<updateTime/sec>";
    public synchronized String ac_lb_set_update_$_1(Args args)
    {
        setUpdateTime(Long.parseLong(args.argv(0)));
        return "";
    }

    public final static String hh_lb_set_threshold = "<threshold>";
    public synchronized String ac_lb_set_threshold_$_1(Args args)
    {
        setUpdateThreshold(Double.parseDouble(args.argv(0)));
        return "";
    }

    private synchronized void sendUpdate()
    {
        if (_loginBroker == null) {
            return;
        }

        LoginBrokerInfo info =
            new LoginBrokerInfo(getCellName(),
                                getCellDomainName(),
                                _protocolFamily,
                                _protocolVersion,
                                _protocolEngine);
        info.setUpdateTime(_brokerUpdateTime * 1000);
        info.setHosts(_hosts);
        info.setPort(_port);

        try {
            sendMessage(new CellMessage(_loginBroker, info));
        } catch (NoRouteToCellException e) {
            _log.error("Failed to send update to " + _loginBroker);
        }
    }

    public synchronized void getInfo(PrintWriter pw)
    {
        if (_loginBroker == null) {
            pw.println("    Login Broker : DISABLED");
            return;
        }
        pw.println("    LoginBroker      : " + _loginBroker);
        pw.println("    Protocol Family  : " + _protocolFamily);
        pw.println("    Protocol Version : " + _protocolVersion);
        pw.println("    Update Time      : " + _brokerUpdateTime +
                   " seconds");
        pw.println("    Update Threshold : " +
                   ((int)(_brokerUpdateThreshold * 100.0)) + " %");

    }

    public synchronized void setAddresses(InetAddress[] addresses)
    {
        _hosts = new String[addresses.length];

        /**
         *  Add addresses ensuring preferred ordering: external
         *  addresses are before any internal interface addresses.
         */
        int nextExternalIfIndex = 0;
        int nextInternalIfIndex = addresses.length - 1;

        for (int i = 0; i < addresses.length; i++) {
            InetAddress addr = addresses[i];

            String host = addr.getHostName();
            if( !addr.isLinkLocalAddress() && !addr.isLoopbackAddress() &&
                !addr.isSiteLocalAddress() && !addr.isMulticastAddress()) {
                _hosts[nextExternalIfIndex++] = host;
            } else {
                _hosts[nextInternalIfIndex--] = host;
            }
        }

        rescheduleTask();
    }

    public synchronized void setPort(int port)
    {
        _port = port;
        rescheduleTask();
    }

    public synchronized void setLoad(int children, int maxChildren)
    {
        double load =
            (maxChildren > 0) ? (double)children / (double)maxChildren : 0.0;
        if (Math.abs(_currentLoad - load) > _brokerUpdateThreshold) {
            rescheduleTask();
        }
        _currentLoad = load;
    }

    public synchronized void setLoginBroker(CellPath loginBroker)
    {
        _loginBroker = loginBroker;
        rescheduleTask();
    }

    public synchronized CellPath getLoginBroker()
    {
        return (CellPath) _loginBroker.clone();
    }

    public synchronized void setProtocolFamily(String protocolFamily)
    {
        _protocolFamily = protocolFamily;
        rescheduleTask();
    }

    public synchronized String getProtocolFamily()
    {
        return _protocolFamily;
    }

    public synchronized void setProtocolVersion(String protocolVersion)
    {
        _protocolVersion = protocolVersion;
        rescheduleTask();
    }

    public synchronized String getProtocolVersion()
    {
        return _protocolVersion;
    }

    public synchronized void setProtocolEngine(String protocolEngine)
    {
        _protocolEngine = protocolEngine;
        rescheduleTask();
    }

    public synchronized String getProtocolEngine()
    {
        return _protocolEngine;
    }

    public synchronized void setUpdateThreshold(double threshold)
    {
        _brokerUpdateThreshold = threshold;
    }

    public synchronized double getUpdateThreshold()
    {
        return _brokerUpdateThreshold;
    }

    public synchronized void setUpdateTime(long time)
    {
        if (time < 2)
            throw new IllegalArgumentException("Update time out of range");
        _brokerUpdateTime = time;
        rescheduleTask();
    }

    public synchronized long setUpdateTime()
    {
        return _brokerUpdateTime;
    }

    public synchronized void setExecutor(ScheduledExecutorService executor)
    {
        _executor = executor;
        rescheduleTask();
    }

    public synchronized void start()
    {
        scheduleTask();
    }

    public synchronized void stop()
    {
        if (_task != null) {
            _task.cancel(false);
            _task = null;
        }
    }

    private void rescheduleTask()
    {
        if (_task != null) {
            _task.cancel(false);
            scheduleTask();
        }
    }

    private void scheduleTask()
    {
        Runnable command = new Runnable() {
                public void run()
                {
                    sendUpdate();
                }
            };
        _task = _executor.scheduleWithFixedDelay(command, 0, _brokerUpdateTime,
                                                 TimeUnit.SECONDS);
    }
}
