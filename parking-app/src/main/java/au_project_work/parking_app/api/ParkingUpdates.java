package au_project_work.parking_app.api;

import au_project_work.parking_app.repository.ParkingLotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tells connected apps when the parking data changed, so they can refresh then
 * instead of asking over and over.
 *
 * <p>
 * The cost of noticing a change does not grow with the number of apps connected:
 * one {@link ParkingLotRepository#fingerprint()} every {@value #CHECK_MS} ms,
 * and only while at least one app is listening. Each app then issues the one
 * GET it would have issued anyway, but only when there is something new.
 */
@Component
public class ParkingUpdates {

    private static final Logger log = LoggerFactory.getLogger(ParkingUpdates.class);

    private static final long CHECK_MS = 2_000;
    /** 15 quiet checks, so a ping roughly every 30 s. */
    private static final int KEEP_ALIVE_TICKS = 15;
    /** Well past CHECK_MS: the stream is long-lived by design, not a hung request. */
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final ParkingLotRepository parkingLotRepository;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private volatile String lastFingerprint;
    private int ticksSinceKeepAlive;
    private boolean failing; // so a database outage is logged once, not every 2 s

    public ParkingUpdates(ParkingLotRepository parkingLotRepository) {
        this.parkingLotRepository = parkingLotRepository;
    }

    /** Registers one app. The returned emitter is what the controller hands back. */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        try {
            // lands immediately so the app knows it is connected and can stop polling hard
            emitter.send(SseEmitter.event().name("hello").data("ok"));
        } catch (IOException e) {
            emitters.remove(emitter);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    @Scheduled(fixedDelay = CHECK_MS)
    void checkForChanges() {
        if (emitters.isEmpty()) {
            // nobody listening: don't touch the database, and forget the old value so the
            // first app to connect is not told about changes it never saw
            lastFingerprint = null;
            return;
        }
        String current;
        try {
            current = parkingLotRepository.fingerprint();
        } catch (Exception e) {
            if (!failing) {
                log.warn("Change check failed, will keep retrying quietly", e);
                failing = true;
            }
            // database hiccup: keep the streams open, try again next tick. The apps still
            // have their own slow poll as a backstop
            return;
        }
        if (failing) {
            log.info("Change check works again");
            failing = false;
        }
        String previous = lastFingerprint;
        lastFingerprint = current;
        if (previous != null && !previous.equals(current)) {
            ticksSinceKeepAlive = 0;
            broadcast("changed");
        } else if (++ticksSinceKeepAlive >= KEEP_ALIVE_TICKS) {
            // nothing changed for a while: one ping so an idle connection is not dropped by
            // a mobile network or a proxy. Costs no database work
            ticksSinceKeepAlive = 0;
            broadcast("keep-alive");
        }
    }

    private void broadcast(String event) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(event).data(""));
            } catch (Exception e) {
                // the app went away mid-send; drop it, the callbacks may not have fired yet
                emitters.remove(emitter);
                emitter.completeWithError(e);
            }
        }
    }
}
