package au_project_work.parking_app.security;

import au_project_work.parking_app.api.AuthController;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.MalformedURLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// the ways the login can be set up besides "configured"; "not configured" is in ParkingAppApplicationTests
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, AdminAuth.class})
class AdminAuthModesTests {

    @Test
    void partialLoginConfigStopsStartUp() {
        new ApplicationContextRunner()
                .withUserConfiguration(AdminAuth.class)
                .withPropertyValues("cognito.region=eu-north-1", "cognito.client-id=test-client")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("COGNITO_USER_POOL_ID").hasMessageContaining("2 of 4"));
    }

    @Test
    void authOffNextToARealLoginStopsStartUp() {
        new ApplicationContextRunner()
                .withUserConfiguration(AdminAuth.class)
                .withPropertyValues("admin.auth=off", "cognito.region=eu-north-1",
                        "cognito.user-pool-id=eu-north-1_Test", "cognito.client-id=test-client",
                        "cognito.domain=https://test.auth.eu-north-1.amazoncognito.com")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("ADMIN_AUTH=off"));
    }

    @Test
    void issuerAndKeysUrlAreThePools() {
        AdminAuth auth = new AdminAuth("eu-north-1", "eu-north-1_AbC123", "client", "https://x.example", "");
        assertThat(auth.issuer()).isEqualTo("https://cognito-idp.eu-north-1.amazonaws.com/eu-north-1_AbC123");
        assertThat(auth.jwksUri())
                .isEqualTo("https://cognito-idp.eu-north-1.amazonaws.com/eu-north-1_AbC123/.well-known/jwks.json");
    }

    @Nested
    @TestPropertySource(properties = "admin.auth=off")
    class AuthOff {

        @Autowired
        private MockMvc mvc;

        @Test
        void everyoneIsAFakeAdminOnAdminRoutesOnly() throws Exception {
            mvc.perform(get("/api/admin/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sub").value("dev-admin"))
                    .andExpect(jsonPath("$.role").value("admin"));
            mvc.perform(get("/api/auth/config")).andExpect(jsonPath("$.mode").value("off"));
            mvc.perform(get("/nope")).andExpect(status().isForbidden());
        }
    }

    @Nested
    @TestPropertySource(properties = {
            "cognito.region=eu-north-1",
            "cognito.user-pool-id=eu-north-1_Test",
            "cognito.client-id=test-client",
            "cognito.domain=https://test.auth.eu-north-1.amazoncognito.com"})
    class JwksUnreachable {

        @Autowired
        private MockMvc mvc;

        @TestBean
        JwtDecoder jwtDecoder;

        // nothing listens on port 1: the keys can't be fetched
        static JwtDecoder jwtDecoder() throws MalformedURLException {
            return SecurityConfig.decoder("http://127.0.0.1:1/jwks.json", TestTokens.ISSUER, TestTokens.CLIENT_ID);
        }

        @Test
        void tokenThatCantBeCheckedIs503() throws Exception {
            mvc.perform(get("/api/admin/me")
                            .header(AUTHORIZATION, "Bearer " + TestTokens.sign(TestTokens.KEY, TestTokens.claims())))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().string(""));
        }
    }
}
