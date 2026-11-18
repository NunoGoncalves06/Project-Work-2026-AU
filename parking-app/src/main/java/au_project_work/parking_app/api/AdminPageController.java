package au_project_work.parking_app.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// the admin dashboard is static files in static/admin/; Spring only serves index.html for the root folder
@Controller
public class AdminPageController {

    @GetMapping("/admin")
    public String redirect() {
        return "redirect:/admin/";
    }

    @GetMapping("/admin/")
    public String index() {
        return "forward:/admin/index.html";
    }
}
