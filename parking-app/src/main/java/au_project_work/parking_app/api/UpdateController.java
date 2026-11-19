package au_project_work.parking_app.api;

import au_project_work.parking_app.service.UpdateService;
import au_project_work.parking_app.service.UpdateService.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// one-click server update; GET is viewer, POST is admin (SecurityConfig). The backend only drops a request file.
@RestController
@RequestMapping("/api/admin/update")
public class UpdateController {

    private final UpdateService service;

    public UpdateController(UpdateService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public Status status() {
        return service.status();
    }

    // admin only (SecurityConfig): the log holds command output
    @GetMapping(value = "/log", produces = MediaType.TEXT_PLAIN_VALUE)
    public String log() {
        return service.log();
    }

    @PostMapping
    public ResponseEntity<?> update(@CurrentSecurityContext(expression = "authentication") Authentication auth) {
        return switch (service.request()) {
            case CREATED -> {
                AdminController.audit(auth, "request update", "");
                yield ResponseEntity.status(HttpStatus.ACCEPTED).body(service.status());
            }
            case BUSY -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "An update is already pending or running"));
            case COOLDOWN -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "cooldown", "retryAt", String.valueOf(service.cooldownEnd())));
            case UNAVAILABLE -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "Update is not available here"));
        };
    }
}
