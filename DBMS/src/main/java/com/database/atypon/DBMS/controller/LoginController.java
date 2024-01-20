package com.database.atypon.DBMS.controller;

import com.database.atypon.DBMS.database_system.Database;
import com.database.atypon.DBMS.database_system.connection.DBConnection;
import com.database.atypon.DBMS.model.User;
import com.database.atypon.DBMS.service.AuthenticationService;

import lombok.AllArgsConstructor;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import javax.servlet.http.HttpServletRequest;

@Controller
@AllArgsConstructor
public class LoginController {

    private final AuthenticationService authenticationService;

    @GetMapping("/login")
    public String login(Model model){
        return "login";
    }

    @PostMapping("/login")
    public String loginPost(User user, HttpServletRequest request, Model model) {
        try {
            String nodeURL = new DBConnection(user).getNodeURL();
            String token = authenticationService.authenticateUser(user, nodeURL);
            request.getSession().setAttribute("token", token);
            request.getSession().setAttribute("nodeURL", nodeURL);
            return "redirect:/dashboard";
        } catch (Exception e) {
            model.addAttribute("error", e.getMessage());
            return "login";
        }
    }

}
