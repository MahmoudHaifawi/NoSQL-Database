package com.database.atypon.Node.controllers.admin;

import com.database.atypon.Node.model.User;
import com.database.atypon.Node.security.SecurityUtils;
import com.database.atypon.Node.services.admin.AdminService;
import com.database.atypon.Node.utils.response.Response;
import com.database.atypon.Node.utils.response.ResponseType;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Vector;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @RequestMapping("/user/add")
    public Vector<Response> addUser(@RequestBody User user) {
        Vector<Response> responses = new Vector<>(List.of(adminService.addUser(user)));

        // Only an ADMIN origin re-broadcasts to peers; an INTERNAL caller is itself a peer.
        if (!"ADMIN".equals(SecurityUtils.currentRole()))
            return responses;

        responses.addAll(adminService.broadcastUser(user));
        return responses;
    }

    @PostMapping(value = "/database/create", produces = "application/json")
    public Vector<Response> createDatabase(@RequestParam String databaseName) throws Exception {

        if(databaseName == null || databaseName.isEmpty())
            return new Vector<>(List.of(new Response(ResponseType.ERROR, "Database name is empty")));

        Vector<Response> responses = new Vector<>(List.of(adminService.createDatabase(databaseName)));

        if(!"ADMIN".equals(SecurityUtils.currentRole()))
            return responses;

        responses.addAll(adminService.broadcastDatabase(databaseName));
        return responses;
    }
}
