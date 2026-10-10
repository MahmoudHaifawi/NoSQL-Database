package com.database.atypon.DBMS.controller;

import com.database.atypon.DBMS.database_system.Database;
import com.database.atypon.DBMS.model.Schema;
import com.database.atypon.DBMS.model.User;
import com.database.atypon.DBMS.service.ClusterService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import javax.servlet.http.HttpServletRequest;

@Controller
public class DashboardController {

    private final ClusterService clusterService;

    public DashboardController(ClusterService clusterService) {
        this.clusterService = clusterService;
    }

    @GetMapping("/dashboard")
    public String getDashboard(Model model, HttpServletRequest request) {
        model.addAttribute("database", new Database());
        model.addAttribute("user", new User());
        model.addAttribute("schema", new Schema());

        // Live cluster topology for the dashboard panel. A failure here (e.g. the bootstrapping node
        // is down) must not break the dashboard, so it degrades to a muted "unavailable" note.
        String token = (String) request.getSession().getAttribute("token");
        String nodeURL = (String) request.getSession().getAttribute("nodeURL");
        try {
            model.addAttribute("nodes", clusterService.topology(token, nodeURL));
        } catch (Exception e) {
            model.addAttribute("topologyError", "Cluster topology unavailable: " + e.getMessage());
        }
        return "dashboard";
    }
}
