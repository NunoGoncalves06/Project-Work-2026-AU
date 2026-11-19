package au_project_work.parking_app.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class ForecastCacheTests {

    @Test
    void keepsTheForecastWithinTheHour() {
        ForecastCache cache = new ForecastCache();
        AtomicInteger runs = new AtomicInteger();
        cache.get(1, () -> { runs.incrementAndGet(); return List.of(); });
        cache.get(1, () -> { runs.incrementAndGet(); return List.of(); });
        assertThat(runs).hasValue(1);
    }

    @Test
    void aForecastComputedWhileAnAdminChangeClearsTheCacheIsNotKept() {
        ForecastCache cache = new ForecastCache();
        AtomicInteger runs = new AtomicInteger();
        cache.get(1, () -> { runs.incrementAndGet(); cache.clear(); return List.of(); }); // admin change lands mid-query
        cache.get(1, () -> { runs.incrementAndGet(); return List.of(); });
        assertThat(runs).hasValue(2);
    }
}
