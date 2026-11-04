package au_project_work.parking_app.api;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-sent events: the app opens this once and is told when to refetch,
 * instead of asking every few seconds. Carries no parking data itself, so the
 * app keeps using the GET endpoints it already has.
 */
@RestController
public class ParkingStreamController {

    private final ParkingUpdates parkingUpdates;

    public ParkingStreamController(ParkingUpdates parkingUpdates) {
        this.parkingUpdates = parkingUpdates;
    }

    @GetMapping(path = "/parking-lots/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return parkingUpdates.subscribe();
    }
}
