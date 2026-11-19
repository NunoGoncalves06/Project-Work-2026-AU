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

    public List<ForecastDto> get(int lotId, Supplier<List<ForecastDto>> compute) {
        Instant hour = Instant.now().truncatedTo(ChronoUnit.HOURS);
        Cached c = byLot.get(lotId);
        if (c != null && c.hour().equals(hour)) {
            return c.forecast();
        }
        List<ForecastDto> fresh = compute.get();
        byLot.put(lotId, new Cached(hour, fresh));
        return fresh;
    }

    /** Clears now and, inside a transaction, again after commit: a request between the two could cache the old forecast. */
    public void clear() {
        byLot.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    byLot.clear();
                }
            });
        }
    }
}
