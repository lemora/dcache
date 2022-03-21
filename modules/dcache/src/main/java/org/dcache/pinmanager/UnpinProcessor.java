package org.dcache.pinmanager;

import static org.dcache.pinmanager.model.Pin.State.FAILED_TO_UNPIN;
import static org.dcache.pinmanager.model.Pin.State.READY_TO_UNPIN;
import static org.dcache.pinmanager.model.Pin.State.UNPINNING;

import diskCacheV111.poolManager.PoolSelectionUnit;
import diskCacheV111.util.CacheException;
import diskCacheV111.vehicles.PoolSetStickyMessage;
import dmg.cells.nucleus.CellPath;
import java.time.Duration;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.jdo.JDOException;
import org.dcache.cells.AbstractMessageCallback;
import org.dcache.cells.CellStub;
import org.dcache.pinmanager.model.Pin;
import org.dcache.poolmanager.PoolMonitor;
import org.dcache.util.CDCExecutorServiceDecorator;
import org.dcache.util.NDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.remoting.RemoteConnectFailureException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Performs the work of unpinning files.
 * <p>
 * When an unpin request is received a pin is put into state READY_TO_UNPIN. The actual work of
 * unpinning a file is performed independently of the unpin request.
 * <p>
 * This class attempts to unpin a limited number of files per run which are in state
 * READY_TO_UNPIN.
 */
public class UnpinProcessor implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(UnpinProcessor.class);

    private static final int MAX_RUNNING = 1000;
    private static final int NO_UNPIN_LIMIT_PER_RUN = -1;

    private final PinDao _dao;
    private final CellStub _poolStub;
    private final PoolMonitor _poolMonitor;
    private final AtomicInteger _count = new AtomicInteger();
    private final int _maxUnpinsPerRun;

    private final ConcurrentHashMap<String, Long> _poolsBlackList = new ConcurrentHashMap<>();
    private Duration _maxBlacklistDuration;


    public UnpinProcessor(PinDao dao, CellStub poolStub, PoolMonitor poolMonitor,
          int maxUnpinsPerRun, Duration maxBlacklistDuration) {
        _dao = dao;
        _poolStub = poolStub;
        _poolMonitor = poolMonitor;
        _maxUnpinsPerRun = maxUnpinsPerRun;
        _maxBlacklistDuration = maxBlacklistDuration;
    }

    @Override
    public void run() {
        updateBlacklistedPools();

        final ExecutorService executor = new CDCExecutorServiceDecorator(
              Executors.newSingleThreadExecutor());
        NDC.push("BackgroundUnpinner-" + _count.incrementAndGet());
        try {
            Semaphore idle = new Semaphore(MAX_RUNNING);
            unpin(idle, executor);
            idle.acquire(MAX_RUNNING);
        } catch (InterruptedException e) {
            LOGGER.debug(e.toString());
        } catch (JDOException | DataAccessException e) {
            LOGGER.error("Database failure while unpinning: {}",
                  e.getMessage());
        } catch (RemoteConnectFailureException e) {
            LOGGER.error("Remote connection failure while unpinning: {}", e.getMessage());
        } catch (RuntimeException e) {
            LOGGER.error("Unexpected failure while unpinning", e);
        } finally {
            executor.shutdown();
            NDC.pop();
        }
    }

    private void updateBlacklistedPools() {
        if (!_poolsBlackList.isEmpty()) {
            LOGGER.debug("{} pools are currently blacklisted: [{}]", _poolsBlackList.size(),
                  _poolsBlackList.entrySet().stream().map(Entry::getKey).collect(
                        Collectors.joining(",")));
            for (Map.Entry<String, Long> blackListEntry : _poolsBlackList.entrySet()) {
                String poolName = blackListEntry.getKey();
                long durationBlacklisted = blackListEntry.getValue();
                // check if it is time to remove pool from the blacklist
                if (durationBlacklisted != 0 && ((System.currentTimeMillis() - durationBlacklisted)
                      > _maxBlacklistDuration.toMillis())) {
                    _poolsBlackList.remove(poolName);
                    LOGGER.warn("Removed pool {} from the unpinning blacklist", poolName);
                }
            }
        }
    }

    @Transactional
    protected void unpin(final Semaphore idle, final Executor executor)
          throws InterruptedException {
        if (_maxUnpinsPerRun == NO_UNPIN_LIMIT_PER_RUN) {
            _dao.foreach(_dao.where().state(READY_TO_UNPIN), pin -> upin(idle, executor, pin));
        } else {
            _dao.foreach(_dao.where().state(READY_TO_UNPIN), pin -> upin(idle, executor, pin),
                  _maxUnpinsPerRun);
        }
    }

    private void upin(Semaphore idle, Executor executor, Pin pin) throws InterruptedException {
        if (pin.getPool() == null) {
            LOGGER.debug("No pool found for pin {}, pnfsid {}; no sticky flags to clear",
                  pin.getPinId(), pin.getPnfsId());
            _dao.delete(pin);
        } else {
            LOGGER.debug("Clearing sticky flag for pin {}, pnfsid {} on pool {}", pin.getPinId(),
                  pin.getPnfsId(), pin.getPool());
            _dao.update(pin, _dao.set().state(UNPINNING));
            clearStickyFlag(idle, pin, executor);
        }
    }

    private void failedToUnpin(Pin pin) {
        _dao.update(pin, _dao.set().state(FAILED_TO_UNPIN));
    }

    private void clearStickyFlag(final Semaphore idle, final Pin pin, Executor executor)
          throws InterruptedException {
        String poolname = pin.getPool();
        if (poolname != null && _poolsBlackList.containsKey(poolname)) {
            LOGGER.debug("Cannot unpin {} because pool {} is blacklisted", pin.getPinId(),
                  poolname);
            failedToUnpin(pin);
            return;
        }
        PoolSelectionUnit.SelectionPool pool = _poolMonitor.getPoolSelectionUnit()
              .getPool(poolname);
        if (pool == null) {
            LOGGER.debug(
                  "Unable to clear sticky flag for pin {} on pnfsid {} because pool is null.",
                  pin.getPinId(), pin.getPnfsId());
        } else if (!pool.isActive()) {
            LOGGER.warn(
                  "Unable to clear sticky flag for pin {} on pnfsid {} because pool {} is unavailable. Blacklisting pool.",
                  pin.getPinId(), pin.getPnfsId(), poolname);
            _poolsBlackList.put(poolname, System.currentTimeMillis());
            failedToUnpin(pin);
            return;
        }

        idle.acquire();
        PoolSetStickyMessage msg =
              new PoolSetStickyMessage(poolname,
                    pin.getPnfsId(),
                    false,
                    pin.getSticky(),
                    0);
        CellStub.addCallback(_poolStub.send(new CellPath(pool.getAddress()), msg),
              new AbstractMessageCallback<PoolSetStickyMessage>() {
                  @Override
                  public void success(PoolSetStickyMessage msg) {
                      idle.release();
                      _dao.delete(pin);
                  }

                  @Override
                  public void failure(int rc, Object error) {
                      idle.release();
                      switch (rc) {
                          case CacheException.FILE_NOT_IN_REPOSITORY:
                              _dao.delete(pin);
                              break;
                          default:
                              LOGGER.warn("Failed to clear sticky flag: {} [{}]", error, rc);
                              failedToUnpin(pin);
                              break;
                      }
                  }
              }, executor);
    }
}