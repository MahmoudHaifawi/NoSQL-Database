package com.database.atypon.DBMS.controller;

import com.database.atypon.DBMS.model.User;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Serves the login page. The POST is processed by Spring Security's form-login
 * ({@code NodeAuthenticationProvider} calls the cluster); this controller only renders the page and
 * surfaces a failure message on the {@code ?error} redirect.
 */
@Controller
public class LoginController {

    @GetMapping("/login")
    public String login(@RequestParam(required = false) String error, Model model) {
        if (!model.containsAttribute("user")) {
            model.addAttribute("user", new User());
        }
        if (error != null) {
            model.addAttribute("error", "Invalid username or password");
        }
        return "login";
    }
}
