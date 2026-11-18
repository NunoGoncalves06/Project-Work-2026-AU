package au_project_work.parking_app;

import au_project_work.parking_app.api.UpdateController;
import au_project_work.parking_app.security.AdminAuth;
import au_project_work.parking_app.security.SecurityConfig;
import au_project_work.parking_app.service.UpdateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// routes and roles with login configured (no real tokens: jwt() sets the caller, no header means anonymous)
@WebMvcTest(controllers = UpdateController.class, properties = {
        "cognito.region=eu-north-1",
        "cognito.user-pool-id=eu-north-1_Test",
        "cognito.client-id=test-client",
        "cognito.domain=https://test.auth.eu-north-1.amazoncognito.com"})
@Import({SecurityConfig.class, AdminAuth.class, UpdateService.class})
class UpdateControllerTests {

    static Path dir;

    @DynamicPropertySource
    static void dir(DynamicPropertyRegistry registry) throws IOException {
        dir = Files.createTempDirectory("au-update");
        Files.createDirectory(dir.resolve("inbox"));
        registry.add("admin.update.dir", dir::toString);
    }

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mvc;

    private static RequestPostProcessor as(String role) {
        return jwt().jwt(j -> j.subject("sub-1").claim("username", "ana")).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    void viewerReadsStatusButCannotRequestAnUpdate() throws Exception {
        mvc.perform(get("/api/admin/update/status").with(as("VIEWER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("Unavailable"));
        mvc.perform(post("/api/admin/update").with(as("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/update").with(as("OPERATOR"))).andExpect(status().isForbidden());
        assertFalse(Files.exists(dir.resolve("inbox/request")));
    }

    @Test
    void adminCanReadTheStatus() throws Exception {
        mvc.perform(get("/api/admin/update/status").with(as("ADMIN"))).andExpect(status().isOk());
    }

    @Test
    void anonymousIsRefusedWith401() throws Exception {
        mvc.perform(get("/api/admin/update/status")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/update")).andExpect(status().isUnauthorized());
        assertFalse(Files.exists(dir.resolve("inbox/request")));
    }

    @Test
    void adminCreatesTheRequestOnceThenGets409() throws Exception {
        mvc.perform(post("/api/admin/update").with(as("ADMIN"))).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("Updating"));
        assertTrue(Files.exists(dir.resolve("inbox/request")));
        mvc.perform(post("/api/admin/update").with(as("ADMIN"))).andExpect(status().isConflict());
        Files.delete(dir.resolve("inbox/request"));
    }

    @Test
    void missingFolderIs503() throws Exception {
        Path inbox = dir.resolve("inbox");
        Path moved = dir.resolve("inbox-moved");
        Files.move(inbox, moved);
        try {
            mvc.perform(post("/api/admin/update").with(as("ADMIN"))).andExpect(status().isServiceUnavailable());
        } finally {
            Files.move(moved, inbox);
        }
    }
}
