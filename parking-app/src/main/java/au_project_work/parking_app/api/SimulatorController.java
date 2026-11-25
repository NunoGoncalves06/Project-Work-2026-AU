package au_project_work.parking_app.api;

import au_project_work.parking_app.api.Dtos.GateReadingRequest;
import au_project_work.parking_app.api.Dtos.SpotReadingRequest;
import au_project_work.parking_app.api.Dtos.StartTrafficRequest;
import au_project_work.parking_app.api.Dtos.TrafficStatusDto;
import au_project_work.parking_app.service.SimulatorService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// simulators; operator and up, decided in SecurityConfig (/api/admin/simulator/**)
@RestController
@RequestMapping("/api/admin/simulator")
public class SimulatorController {

    private final SimulatorService service;

    public SimulatorController(SimulatorService service) {
        this.service = service;
    }

    @PostMapping("/spots/{spotId}")
    public void setSpot(@PathVariable int spotId, @Valid @RequestBody SpotReadingRequest body,
            @CurrentSecurityContext(expression = "authentication") Authentication auth) {
        service.setSpot(spotId, body.occupied());
        AdminController.audit(auth, "simulate spot", "spot=" + spotId + " occupied=" + body.occupied());
    }

    @PostMapping("/lots/{lotId}/entrance")
    public void gate(@PathVariable int lotId, @Valid @RequestBody GateReadingRequest body,
            @CurrentSecurityContext(expression = "authentication") Authentication auth) {
        service.gate(lotId, body.dir());
        AdminController.audit(auth, "simulate gate", "lot=" + lotId + " dir=" + body.dir());
    }

    @PostMapping("/traffic")
    @ResponseStatus(HttpStatus.CREATED)
    public TrafficStatusDto start(@Valid @RequestBody StartTrafficRequest body,
            @CurrentSecurityContext(expression = "authentication") Authentication auth) {
        service.start(body.lotId(), body.targetOccupancy(), body.changesPerMinute());
        AdminController.audit(auth, "start traffic", "lot=" + body.lotId() + " target=" + body.targetOccupancy()
                + " changesPerMinute=" + body.changesPerMinute());
        return service.status();
    }

    @DeleteMapping("/traffic")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stop(@CurrentSecurityContext(expression = "authentication") Authentication auth) {
        Integer lot = service.stop();
        if (lot != null) {
            AdminController.audit(auth, "stop traffic", "lot=" + lot);
        }
    }

    @GetMapping("/traffic")
    public TrafficStatusDto status() {
        return service.status();
    }
}
