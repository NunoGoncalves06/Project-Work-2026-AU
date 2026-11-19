package au_project_work.parking_app.api;

import au_project_work.parking_app.api.Dtos.ForecastDto;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The 168-hour forecast of a lot, kept until the hour changes: the sample times are hour-aligned, so
 * it cannot change within the hour. Cleared by the admin API when spots or sensors change.
 */
@Component
public class ForecastCache {

    private record Cached(Instant hour, List<ForecastDto> forecast) {
    }

    private final Map<Integer, Cached> byLot = new ConcurrentHashMap<>();
    /** Bumped by every clear(): a forecast computed before a clear is not stored after it. */
    private final AtomicLong generation = new AtomicLong();

    public List<ForecastDto> get(int lotId, Supplier<List<ForecastDto>> compute) {
        // read before the query: its SQL now() is at least this late, so a query that crosses the hour is stored
        // under the earlier hour and recomputed seconds later, never kept a whole hour under the wrong one
        Instant hour = Instant.now().truncatedTo(ChronoUnit.HOURS);
        long gen = generation.get();
        Cached c = byLot.get(lotId);
        if (c != null && c.hour().equals(hour)) {
            return c.forecast();
        }
        List<ForecastDto> fresh = compute.get();
        if (generation.get() == gen) {
            byLot.put(lotId, new Cached(hour, fresh));
        }
        return fresh;
    }

    /** Clears now and, inside a transaction, again after commit: a request between the two could cache the old forecast. */
    public void clear() {
        generation.incrementAndGet();
        byLot.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    generation.incrementAndGet();
                    byLot.clear();
                }
            });
        }
    }
}
