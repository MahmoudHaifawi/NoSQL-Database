package com.database.atypon.Node.controllers.index;

import com.database.atypon.Node.index.BPlusTree;
import com.database.atypon.Node.model.Network;
import com.database.atypon.Node.model.Node;
import com.database.atypon.Node.services.authentication.AuthenticationService;
import com.database.atypon.Node.services.index.IndexManager;
import com.database.atypon.Node.utils.Token;
import com.database.atypon.Node.utils.response.Response;
import com.database.atypon.Node.utils.response.ResponseType;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Vector;

@RestController
public class IndexController {

    private final IndexManager indexManager;
    private final AuthenticationService authenticationService;

    public IndexController(IndexManager indexManager, AuthenticationService authenticationService) {
        this.indexManager = indexManager;
        this.authenticationService = authenticationService;
    }

    @PostMapping(value = "/admin/index/create", produces = "application/json")
    public Vector<Response> createIndex(@RequestParam String database, @RequestParam String schema,
                                        @RequestParam String field, @RequestHeader("authorization") String token) {
        if (!authenticationService.isAdminToken(token)) {
            return one(new Response(ResponseType.ERROR, "You are not an admin"));
        }
        Vector<Response> responses = new Vector<>();
        try {
            indexManager.createIndex(database, schema, field);
            responses.add(new Response(ResponseType.SUCCESS, "Index created on " + schema + "." + field));
        } catch (Exception e) {
            responses.add(new Response(ResponseType.ERROR, e.getMessage()));
        }
        if (!token.equals(Token.ADMIN)) {
            return responses;
        }
        for (Node node : Network.nodes) {
            responses.add(node.createIndex(database, schema, field));
        }
        return responses;
    }

    @PostMapping(value = "/admin/index/drop", produces = "application/json")
    public Vector<Response> dropIndex(@RequestParam String database, @RequestParam String schema,
                                      @RequestParam String field, @RequestHeader("authorization") String token) {
        if (!authenticationService.isAdminToken(token)) {
            return one(new Response(ResponseType.ERROR, "You are not an admin"));
        }
        Vector<Response> responses = new Vector<>();
        try {
            indexManager.dropIndex(database, schema, field);
            responses.add(new Response(ResponseType.SUCCESS, "Index dropped on " + schema + "." + field));
        } catch (Exception e) {
            responses.add(new Response(ResponseType.ERROR, e.getMessage()));
        }
        if (!token.equals(Token.ADMIN)) {
            return responses;
        }
        for (Node node : Network.nodes) {
            responses.add(node.dropIndex(database, schema, field));
        }
        return responses;
    }

    @GetMapping(value = "/admin/index/list", produces = "application/json")
    public Vector<Response> listIndexes(@RequestParam String database, @RequestParam String schema,
                                        @RequestHeader("authorization") String token) {
        if (!authenticationService.isAdminToken(token)) {
            return one(new Response(ResponseType.ERROR, "You are not an admin"));
        }
        try {
            List<String> fields = indexManager.listIndexes(database, schema);
            return one(new Response(ResponseType.SUCCESS, "Indexed fields", fields.toString()));
        } catch (Exception e) {
            return one(new Response(ResponseType.ERROR, e.getMessage()));
        }
    }

    @PostMapping(value = "/user/index/query", produces = "application/json")
    public Response query(@RequestBody HashMap<String, Object> body, @RequestHeader("authorization") String token) {
        if (!authenticationService.isUserToken(token)) {
            return new Response(ResponseType.ERROR, "You are not a user");
        }
        try {
            String database = (String) body.get("database");
            String schema = (String) body.get("schema");
            String field = (String) body.get("field");
            BPlusTree.Op op = BPlusTree.Op.valueOf(((String) body.get("op")).toUpperCase());
            Object value = body.get("value");
            Object high = body.get("high");
            boolean ascending = !"DESC".equalsIgnoreCase(String.valueOf(body.getOrDefault("order", "ASC")));
            int limit = body.containsKey("limit") ? ((Number) body.get("limit")).intValue() : -1;
            int offset = body.containsKey("offset") ? ((Number) body.get("offset")).intValue() : 0;
            List<Integer> ids = indexManager.query(database, schema, field, op, value, high, ascending, offset, limit);
            return new Response(ResponseType.SUCCESS, "Query results", ids.toString());
        } catch (Exception e) {
            return new Response(ResponseType.ERROR, e.getMessage());
        }
    }

    private Vector<Response> one(Response r) {
        Vector<Response> v = new Vector<>();
        v.add(r);
        return v;
    }
}
