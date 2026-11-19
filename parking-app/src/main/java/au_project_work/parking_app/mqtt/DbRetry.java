package au_project_work.parking_app.mqtt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/** Keeps a sensor reading through a short database outage instead of dropping it. */
final class DbRetry {
    private static final Logger log = LoggerFactory.getLogger(DbRetry.class);
    private static final long GIVE_UP_AFTER_MS = 30_000;

    private DbRetry() {
    }

    /** Runs the action; on a database error waits 1 s, 2 s, 4 s... and retries, for about 30 s in all. */
    static void run(Runnable action) {
        long waited = 0;
        long delay = 1_000;
        while (true) {
            try {
                action.run();
                return;
            } catch (DataAccessException e) {
                if (waited + delay > GIVE_UP_AFTER_MS) {
                    log.error("Database still failing after {} ms, dropping the sensor reading", waited, e);
                    return;
                }
                log.warn("Database error, retrying in {} ms: {}", delay, e.getMessage());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                waited += delay;
                delay *= 2;
            }
        }
    }
}
