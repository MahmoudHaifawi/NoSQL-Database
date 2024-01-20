package com.database.atypon.DBMS.controller;

import com.database.atypon.DBMS.database_system.Database;
import com.database.atypon.DBMS.model.User;
import com.database.atypon.DBMS.service.AdminService;

import lombok.AllArgsConstructor;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.servlet.http.HttpServletRequest;

@Controller
@AllArgsConstructor
@RequestMapping("/admin")
public class AdminController {

    private final AdminService adminService;

    @PostMapping("/createDatabase")
    public String createDatabase(Database database, HttpServletRequest request, Model model) {
        try {
            String token = (String) request.getSession().getAttribute("token");
            String nodeURL = (String) request.getSession().getAttribute("nodeURL");
            adminService.createDatabase(database.getName(), token, nodeURL);
            return "redirect:/dashboard";
        } catch (Exception e) {
            System.err.println("Error creating database: " + e.getMessage());
            model.addAttribute("databaseError", e.getMessage());
            return "redirect:/dashboard";
        }
    }

    @PostMapping("/addUser")
    public String addUser(User user, HttpServletRequest request, Model model) {
        try {
            String token = (String) request.getSession().getAttribute("token");
            String nodeURL = (String) request.getSession().getAttribute("nodeURL");
            System.out.println(adminService.addUser(user, token, nodeURL));
            return "redirect:/dashboard";
        } catch (Exception e) {
            System.err.println("Error adding user: " + e.getMessage());
            model.addAttribute("userError", e.getMessage());
            return "redirect:/dashboard";
        }
    }

}
