package au_project_work.parking_app.api;

import au_project_work.parking_app.api.Dtos.AdminLotDto;
import au_project_work.parking_app.api.Dtos.CountingRequest;
import au_project_work.parking_app.api.Dtos.CreateLotRequest;
import au_project_work.parking_app.api.Dtos.IdDto;
import au_project_work.parking_app.api.Dtos.MoveSensorRequest;
import au_project_work.parking_app.api.Dtos.ParkedSensorDto;
import au_project_work.parking_app.api.Dtos.SensorRequest;
import au_project_work.parking_app.api.Dtos.SpotRequest;
import au_project_work.parking_app.service.AdminService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

// lot, row, spot and sensor management; who may call what is in SecurityConfig (GET viewer, changes admin)
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    // one INFO line per change; ids only, never request text
    private static final Logger AUDIT = LoggerFactory.getLogger("audit");

    private final AdminService service;

    public AdminController(AdminService service) {
        this.service = service;
    }

    @GetMapping("/lots")
    public List<AdminLotDto> lots() {
        return service.lots();
    }

    @GetMapping("/sensors/parked")
    public List<ParkedSensorDto> parkedSensors() {
        return service.parkedSensors();
    }

    @PostMapping("/lots")
    @ResponseStatus(HttpStatus.CREATED)
    public IdDto createLot(@Valid @RequestBody CreateLotRequest body, @CurrentSecurityContext(expression = "authentication") Authentication auth) {
        int id = service.createLot(body);
        audit(auth, "create lot", "lot=" + id);
        return new IdDto(id);
    }

    @PutMapping("/lots/{id}/counting")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setCounting(@PathVariable int id, @Valid @RequestBody CountingRequest body,
            @CurrentSecurityContext(expression = "authentication") Authentication auth) {
        service.setCounting(id, body.countedBy());
        audit(auth, "set lot counting", "lot=" + id + " countedBy=" + body.countedBy());
    }

    @PostMapping("/lots/{lotId}/rows")
    @ResponseStatus(HttpStatus.CREATED)
    public IdDto addRow(@PathVariable int lotId, @CurrentSecurityContext(expression = "authentication") Authentication auth) {
        int id = service.addRow(lotId);
        audit(auth, "create row", "lot=" + lotId + " row=" + id);
        return new IdDto(id);
    }

    @PostMapping("/rows/{rowId}/spots")
    @ResponseStatus(HttpStatus.CREATED)
    public IdDto addSpot(@PathVariable int rowId, @Valid @RequestBody SpotRequest body,
            @CurrentSecurityContext(expression = "authentication") Authentication auth) {
        int id = service.addSpot(rowId, body.sensorId());
        audit(auth, "create spot", "row=" + rowId + " spot=" + id + " sensor=" + body.sensorId());
        return new IdDto(id);
    }

    @PostMapping("/lots/{lotId}/entrance-sensors")
    @ResponseStatus(HttpStatus.CREATED)
    public IdDto addEntranceSensor(@PathVariable int lotId, @Valid @RequestBody SensorRequest body,
            @CurrentSecurityContext(expression = "authentication") Authentication auth) {
        service.addEntranceSensor(lotId, body.sensorId());
        audit(auth, "create entrance sensor", "lot=" + lotId + " sensor=" + body.sensorId());
        return new IdDto(body.sensorId());
    }

    @PutMapping("/sensors/{sensorId}/spot")
    public void moveSensor(@PathVariable int sensorId, @Valid @RequestBody MoveSensorRequest body,
            @CurrentSecurityContext(expression = "authentication") Authentication auth) {
        service.moveSensor(sensorId, body.spotId());
        audit(auth, "move sensor", "sensor=" + sensorId + " spot=" + body.spotId());
    }

    @DeleteMapping("/lots/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveLot(@PathVariable int id, @CurrentSecurityContext(expression = "authentication") Authentication auth) {
        service.archiveLot(id);
        audit(auth, "archive lot", "lot=" + id);
    }

    @DeleteMapping("/rows/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveRow(@PathVariable int id, @CurrentSecurityContext(expression = "authentication") Authentication auth) {
        service.archiveRow(id);
        audit(auth, "archive row", "row=" + id);
    }

    @DeleteMapping("/spots/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveSpot(@PathVariable int id, @CurrentSecurityContext(expression = "authentication") Authentication auth) {
        service.archiveSpot(id);
        audit(auth, "archive spot", "spot=" + id);
    }

    // same identity as /api/admin/me: JWT sub and username, or the placeholder user when ADMIN_AUTH=off
    static void audit(Authentication auth, String action, String ids) {
        String username = auth.getPrincipal() instanceof Jwt jwt ? jwt.getClaimAsString("username") : auth.getName();
        AUDIT.info("{} by sub={} username={} {}", action, clean(auth.getName()), clean(username), ids);
    }

    // the username is user-controlled text: no line breaks or control characters in the log
    private static String clean(String s) {
        return s == null ? null : s.replaceAll("[\\r\\n\\p{Cntrl}]", "_");
    }

    // short generic messages: never echo the input or the database error
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, String>> badInput() {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid input"));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<Map<String, String>> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Already in use"));
    }
}
