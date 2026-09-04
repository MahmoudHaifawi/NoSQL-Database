package com.database.atypon.Node.services.admin;

import com.database.atypon.Node.model.Network;
import com.database.atypon.Node.model.Node;
import com.database.atypon.Node.model.User;
import com.database.atypon.Node.operations.admin.AdminOperations;
import com.database.atypon.Node.utils.concurrent.Broadcaster;
import com.database.atypon.Node.utils.response.Response;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.function.Supplier;

@Service
public class AdminService {

    private final AdminOperations adminOperations;

    public AdminService(AdminOperations adminOperations) {
        this.adminOperations = adminOperations;
    }

    public Response createDatabase(String databaseName) throws Exception {
        return adminOperations.createDatabase(databaseName);
    }
    public Response addUser(User user) {
        return adminOperations.addUser(user);
    }

    public List<Response> broadcastUser(User user) {
        List<Response> res = new Vector<>();
        for (Node node : Network.nodes) {
            res.add(node.addUser(user));
        }
        return res;
    }
    public List<Response> broadcastDatabase(String databaseName){
        List<Supplier<Response>> tasks = new ArrayList<>();
        for (Node node : Network.nodes) {
            tasks.add(() -> node.addDatabase(databaseName));
        }
        return Broadcaster.broadcast(tasks);
    }
}
