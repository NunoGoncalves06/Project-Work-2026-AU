package au_project_work.parking_app;

import au_project_work.parking_app.api.AdminPageController;
import au_project_work.parking_app.security.AdminAuth;
import au_project_work.parking_app.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// the dashboard page is public (the login happens in the browser); no COGNITO_* set here, i.e. "not configured"
@WebMvcTest(controllers = AdminPageController.class)
@Import({SecurityConfig.class, AdminAuth.class})
class AdminPageTests {

    @Autowired
    private MockMvc mvc;

    @Test
    void adminWithoutSlashRedirects() throws Exception {
        mvc.perform(get("/admin")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/"));
    }

    @Test
    void adminFolderServesTheIndexWithoutALogin() throws Exception {
        mvc.perform(get("/admin/")).andExpect(status().isOk()).andExpect(forwardedUrl("/admin/index.html"));
    }
}
