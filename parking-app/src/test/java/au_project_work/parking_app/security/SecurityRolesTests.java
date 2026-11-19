package au_project_work.parking_app.security;

import au_project_work.parking_app.api.AuthController;
import com.nimbusds.jose.jwk.JWKSet;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static au_project_work.parking_app.security.TestTokens.KEY;
import static au_project_work.parking_app.security.TestTokens.OTHER_KEY;
import static au_project_work.parking_app.security.TestTokens.claims;
import static au_project_work.parking_app.security.TestTokens.sign;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// login configured: the production decoder and validators, with this run's key served as the pool's JWKS
@WebMvcTest(controllers = AuthController.class, properties = {
        "cognito.region=eu-north-1",
        "cognito.user-pool-id=eu-north-1_Test",
        "cognito.client-id=test-client",
        "cognito.domain=test.auth.eu-north-1.amazoncognito.com"})
@Import({SecurityConfig.class, AdminAuth.class, SecurityRolesTests.StandInRoutes.class})
class SecurityRolesTests {

    static final HttpServer JWKS = jwksServer();

    @TestBean
    JwtDecoder jwtDecoder;

    static JwtDecoder jwtDecoder() throws MalformedURLException {
        return SecurityConfig.decoder("http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks.json",
                TestTokens.ISSUER, TestTokens.CLIENT_ID);
    }

    @AfterAll
    static void stopJwks() {
        JWKS.stop(0);
    }

    // stand-ins for the routes Steps 3-5 add, so their minimum roles are tested before they exist,
    // and for the app's lot list, whose real controller needs the database
    @RestController
    static class StandInRoutes {
        @RequestMapping({"/api/admin/lots/1", "/api/admin/simulator/1", "/api/admin/update/1", "/parking-lots"})
        String ok() {
            return "ok";
        }
    }

    @Autowired
    private MockMvc mvc;

    // a decoded token as Cognito would send it; its groups go through the real groups-to-role mapping
    private static RequestPostProcessor as(String group) {
        return jwt().jwt(j -> j.subject("user-1").claim("cognito:groups", List.of(group)))
                .authorities(SecurityConfig::authorities);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            // method, path,            anonymous, no role, viewer, operator, admin
            "GET,  /api/admin/me,          401, 403, 200, 200, 200",
            "GET,  /api/admin/lots/1,      401, 403, 200, 200, 200",
            "POST, /api/admin/lots/1,      401, 403, 403, 403, 200",
            "GET,  /api/admin/simulator/1, 401, 403, 403, 200, 200",
            "POST, /api/admin/simulator/1, 401, 403, 403, 200, 200",
            "GET,  /api/admin/update/1,    401, 403, 200, 200, 200",
            "POST, /api/admin/update/1,    401, 403, 403, 403, 200"})
    void roleMatrix(String method, String path, int anonymous, int noRole, int viewer, int operator, int admin)
            throws Exception {
        HttpMethod m = HttpMethod.valueOf(method);
        mvc.perform(request(m, path)).andExpect(status().is(anonymous));
        mvc.perform(request(m, path).with(as("staff"))).andExpect(status().is(noRole));
        mvc.perform(request(m, path).with(as("viewer"))).andExpect(status().is(viewer));
        mvc.perform(request(m, path).with(as("operator"))).andExpect(status().is(operator));
        mvc.perform(request(m, path).with(as("admin"))).andExpect(status().is(admin));
    }

    @Test
    void roleIsTheHighestKnownGroup() {
        assertEquals(Optional.of("admin"), SecurityConfig.roleOf(List.of("viewer", "admin", "operator")));
        assertEquals(Optional.of("operator"), SecurityConfig.roleOf(List.of("staff", "operator")));
        assertEquals(Optional.empty(), SecurityConfig.roleOf(List.of("staff", "Admin")));
        assertEquals(Optional.empty(), SecurityConfig.roleOf(List.of()));
        assertEquals(Optional.empty(), SecurityConfig.roleOf(null));
    }

    @Test
    void signedAccessTokenGetsItsHighestRole() throws Exception {
        String token = sign(KEY, claims().claim("cognito:groups", List.of("staff", "viewer", "operator")));
        mvc.perform(get("/api/admin/me").header(AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sub").value("user-1"))
                .andExpect(jsonPath("$.username").value("ana"))
                .andExpect(jsonPath("$.role").value("operator"));
    }

    @Test
    void signedTokenWithoutGroupIs403() throws Exception {
        String token = sign(KEY, claims().claim("cognito:groups", null));
        mvc.perform(get("/api/admin/me").header(AUTHORIZATION, "Bearer " + token)).andExpect(status().isForbidden());
    }

    static Stream<Arguments> badTokens() {
        return Stream.of(
                Arguments.of("wrong issuer", sign(KEY, claims().issuer("https://cognito-idp.eu-north-1.amazonaws.com/eu-north-1_Other"))),
                Arguments.of("wrong client_id", sign(KEY, claims().claim("client_id", "other-client"))),
                Arguments.of("ID token", sign(KEY, claims().claim("token_use", "id"))),
                Arguments.of("expired", sign(KEY, claims().expirationTime(Date.from(Instant.now().minusSeconds(120))))),
                Arguments.of("no exp", sign(KEY, claims().expirationTime(null))),
                Arguments.of("no token_use", sign(KEY, claims().claim("token_use", null))),
                Arguments.of("no sub", sign(KEY, claims().subject(null))),
                Arguments.of("bad signature", sign(OTHER_KEY, claims())),
                Arguments.of("alg none", TestTokens.unsigned(claims())),
                Arguments.of("HS256 with the public key", TestTokens.hs256WithPublicKey(claims())),
                Arguments.of("not a JWT", "abc"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badTokens")
    void badTokenIs401WithoutSayingWhy(String why, String token) throws Exception {
        mvc.perform(get("/api/admin/me").header(AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().string(""));
    }

    @Test
    void noTokenIs401WithBearerChallenge() throws Exception {
        mvc.perform(get("/api/admin/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(WWW_AUTHENTICATE, "Bearer"));
        mvc.perform(head("/api/admin/me")).andExpect(status().isUnauthorized());
        mvc.perform(options("/api/admin/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/me/")).andExpect(status().isUnauthorized());
    }

    @Test
    void publicRoutesIgnoreAStaleToken() throws Exception {
        String expired = sign(KEY, claims().expirationTime(Date.from(Instant.now().minusSeconds(120))));
        mvc.perform(get("/parking-lots").header(AUTHORIZATION, "Bearer " + expired)).andExpect(status().isOk());
        mvc.perform(get("/parking-lots").header(AUTHORIZATION, "Bearer abc")).andExpect(status().isOk());
    }

    @Test
    void healthAndLoginConfigArePublic() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
        mvc.perform(get("/api/auth/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("cognito"))
                .andExpect(jsonPath("$.region").value("eu-north-1"))
                .andExpect(jsonPath("$.userPoolId").value("eu-north-1_Test"))
                .andExpect(jsonPath("$.clientId").value("test-client"))
                .andExpect(jsonPath("$.domain").value("test.auth.eu-north-1.amazoncognito.com"));
    }

    @Test
    void unlistedRoutesAreRefusedEvenForAdmins() throws Exception {
        mvc.perform(get("/nope")).andExpect(status().isUnauthorized());
        mvc.perform(get("/nope").with(as("admin"))).andExpect(status().isForbidden());
        mvc.perform(post("/parking-lots").with(as("admin"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/health").with(as("admin"))).andExpect(status().isForbidden());
    }

    private static HttpServer jwksServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks.json", exchange -> {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
