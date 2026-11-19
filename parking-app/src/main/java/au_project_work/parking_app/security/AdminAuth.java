package au_project_work.parking_app.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.stream.Stream;

/**
 * How /api/admin/** is protected, decided once at start-up from COGNITO_* and ADMIN_AUTH.
 * Fails closed: without the Cognito values nobody gets in, unless ADMIN_AUTH=off says so explicitly.
 */
@Component
public class AdminAuth {

    private static final Logger log = LoggerFactory.getLogger(AdminAuth.class);

    public enum Mode { COGNITO, OFF, UNCONFIGURED }

    public final Mode mode;
    public final String region;
    public final String userPoolId;
    public final String clientId;
    public final String domain;

    AdminAuth(@Value("${cognito.region:}") String region,
              @Value("${cognito.user-pool-id:}") String userPoolId,
              @Value("${cognito.client-id:}") String clientId,
              @Value("${cognito.domain:}") String domain,
              @Value("${admin.auth:}") String adminAuth) {
        this.region = region.trim();
        this.userPoolId = userPoolId.trim();
        this.clientId = clientId.trim();
        this.domain = domain.trim();
        // the Caddyfile and auth.js both put "https://" in front themselves
        if (this.domain.contains("/") || this.domain.contains(":")) {
            throw new IllegalStateException("COGNITO_DOMAIN must be the host only, without https:// (e.g. x.auth.eu-north-1.amazoncognito.com)");
        }
        long set = Stream.of(this.region, this.userPoolId, this.clientId, this.domain).filter(s -> !s.isEmpty()).count();
        // half a login config is a typo in .env: refuse to start rather than guess
        if (set != 0 && set != 4) {
            throw new IllegalStateException("Set all four of COGNITO_REGION, COGNITO_USER_POOL_ID, COGNITO_CLIENT_ID"
                    + " and COGNITO_DOMAIN, or none of them (" + set + " of 4 are set)");
        }
        boolean off = "off".equalsIgnoreCase(adminAuth.trim());
        // a server with a real login must never be opened by a leftover dev setting
        if (off && set == 4) {
            throw new IllegalStateException("ADMIN_AUTH=off and the COGNITO_* values are both set: remove ADMIN_AUTH");
        }
        if (off) {
            mode = Mode.OFF;
            log.warn("ADMIN_AUTH=off: the admin API is OPEN to anyone, without a login. Local development only, never on a server.");
        } else if (set == 4) {
            mode = Mode.COGNITO;
        } else {
            mode = Mode.UNCONFIGURED;
            log.warn("COGNITO_* not set: every /api/admin request is refused (503) until they are.");
        }
    }

    String issuer() {
        return "https://cognito-idp." + region + ".amazonaws.com/" + userPoolId;
    }

    String jwksUri() {
        return issuer() + "/.well-known/jwks.json";
    }
}
