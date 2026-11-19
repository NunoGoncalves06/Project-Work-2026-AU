package au_project_work.parking_app.security;

import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Who may call what. The app's read routes stay public; /api/admin/** needs a Cognito access token
 * whose group gives the route's minimum role. Everything not listed here is refused.
 */
@Configuration
public class SecurityConfig {

    // Cognito groups that are roles, lowest first; the highest one a user is in wins, other groups are ignored
    static final List<String> ROLES = List.of("viewer", "operator", "admin");

    private static final String[] PUBLIC_READS = {"/parking-lots", "/parking-lots/{id}", "/parking-lots/stream",
            "/api/health", "/api/auth/config", "/admin/**"};

    private static final RequestMatcher ADMIN_API = PathPatternRequestMatcher.withDefaults().matcher("/api/admin/**");

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AdminAuth auth, JwtDecoder jwtDecoder) throws Exception {
        http.csrf(csrf -> csrf.disable()) // bearer tokens only, never cookies, so there is nothing to forge
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .authorizeHttpRequests(a -> a
                        // Spring's error page for a 404/500 on a public route; requesting /error directly stays denied
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_READS).permitAll()
                        .requestMatchers(HttpMethod.HEAD, PUBLIC_READS).permitAll()
                        // minimum role per admin route, decided here and nowhere else; first match wins
                        .requestMatchers("/api/admin/simulator/**").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.GET, "/api/admin/update/log").hasRole("ADMIN") // command output of the last update
                        .requestMatchers(HttpMethod.GET, "/api/admin/**").hasRole("VIEWER") // me, lots, update status
                        .requestMatchers("/api/admin/**").hasRole("ADMIN") // lot/row/spot/sensor changes, update request
                        .anyRequest().denyAll());

        switch (auth.mode) {
            case COGNITO -> {
                AuthenticationEntryPoint entryPoint = (request, response, e) -> {
                    // the signing keys could not be fetched: the token may be fine, we just can't check it now
                    if (e instanceof AuthenticationServiceException) {
                        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
                        return;
                    }
                    // no error_description: never tell the caller which check its token failed
                    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                };
                DefaultBearerTokenResolver bearer = new DefaultBearerTokenResolver();
                http.oauth2ResourceServer(o -> o
                        .jwt(j -> j.decoder(jwtDecoder).jwtAuthenticationConverter(authenticationConverter()))
                        // a stale token on a public route is ignored instead of turning the request into a 401
                        .bearerTokenResolver(request -> ADMIN_API.matches(request) ? bearer.resolve(request) : null)
                        .authenticationEntryPoint(entryPoint)
                        .withObjectPostProcessor(new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                            @Override
                            public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                                // the default handler rethrows a JWKS failure, which becomes a 500
                                filter.setAuthenticationFailureHandler(entryPoint::commence);
                                return filter;
                            }
                        }));
            }
            // every request counts as a logged-in admin; AdminAuth logged a warning at start-up
            case OFF -> http.anonymous(a -> a.principal("dev-admin").authorities("ROLE_ADMIN"));
            // nothing can log in, so every refused request says the service is unavailable
            case UNCONFIGURED -> http.exceptionHandling(e -> e.authenticationEntryPoint(
                    new HttpStatusEntryPoint(HttpStatus.SERVICE_UNAVAILABLE)));
        }
        return http.build();
    }

    @Bean
    static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy("ROLE_ADMIN > ROLE_OPERATOR\nROLE_OPERATOR > ROLE_VIEWER");
    }

    // only used in COGNITO mode; fetches the pool's keys on the first token, not at start-up
    @Bean
    JwtDecoder jwtDecoder(AdminAuth auth) throws MalformedURLException {
        return decoder(auth.jwksUri(), auth.issuer(), auth.clientId);
    }

    static JwtDecoder decoder(String jwksUri, String issuer, String clientId) throws MalformedURLException {
        // keys cached 5 min and refetched at most every 30 s, so tokens with made-up key ids can't flood Cognito
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSource(JWKSourceBuilder.create(URI.create(jwksUri).toURL()).build())
                .build(); // RS256 only
        JwtTimestampValidator timestamps = new JwtTimestampValidator(); // exp/nbf with 60 s clock skew
        timestamps.setAllowEmptyExpiryClaim(false);
        decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(List.<OAuth2TokenValidator<Jwt>>of(
                timestamps,
                new JwtIssuerValidator(issuer),
                new JwtClaimValidator<String>("token_use", "access"::equals),
                // access tokens name the app client in client_id; they have no aud to check
                new JwtClaimValidator<String>("client_id", clientId::equals),
                new JwtClaimValidator<Object>(JwtClaimNames.SUB, Objects::nonNull))));
        return decoder;
    }

    static JwtAuthenticationConverter authenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::authorities);
        return converter;
    }

    // only the highest role is granted; roleHierarchy() gives the ones below it
    static Collection<GrantedAuthority> authorities(Jwt jwt) {
        return roleOf(jwt.getClaimAsStringList("cognito:groups"))
                .<Collection<GrantedAuthority>>map(role -> List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase())))
                .orElse(List.of());
    }

    static Optional<String> roleOf(List<String> groups) {
        return groups == null ? Optional.empty()
                : groups.stream().filter(ROLES::contains).max(Comparator.comparingInt(ROLES::indexOf));
    }
}
