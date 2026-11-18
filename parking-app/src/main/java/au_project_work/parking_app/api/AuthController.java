package au_project_work.parking_app.api;

import au_project_work.parking_app.api.Dtos.AuthConfigDto;
import au_project_work.parking_app.api.Dtos.MeDto;
import au_project_work.parking_app.security.AdminAuth;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// health check, login settings and "who am I" for the admin dashboard; who may call what is in SecurityConfig
@RestController
public class AuthController {

    private final AdminAuth adminAuth;

    public AuthController(AdminAuth adminAuth) {
        this.adminAuth = adminAuth;
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    // public values only: the pool, the public app client (no secret) and the hosted login domain
    @GetMapping("/api/auth/config")
    public AuthConfigDto authConfig() {
        return new AuthConfigDto(adminAuth.mode.name().toLowerCase(), adminAuth.region, adminAuth.userPoolId,
                adminAuth.clientId, adminAuth.domain);
    }

    // resolved from the context, not as a Principal, so the ADMIN_AUTH=off placeholder user works too
    @GetMapping("/api/admin/me")
    public MeDto me(@CurrentSecurityContext(expression = "authentication") Authentication auth) {
        String username = auth.getPrincipal() instanceof Jwt jwt ? jwt.getClaimAsString("username") : auth.getName();
        String role = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_")).map(a -> a.substring(5).toLowerCase())
                .findFirst().orElseThrow();
        return new MeDto(auth.getName(), username, role);
    }
}
