package com.database.atypon.Node.controllers.index;

import com.database.atypon.Node.index.BPlusTree;
import com.database.atypon.Node.model.Network;
import com.database.atypon.Node.model.Node;
import com.database.atypon.Node.security.SecurityUtils;
import com.database.atypon.Node.services.index.IndexManager;
import com.database.atypon.Node.utils.response.Response;
import com.database.atypon.Node.utils.response.ResponseType;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Vector;

@RestController
public class IndexController {

    private final IndexManager indexManager;

    public IndexController(IndexManager indexManager) {
        this.indexManager = indexManager;
    }

    private static boolean isAdminOrigin() {
        return "ADMIN".equals(SecurityUtils.currentRole());
    }

    @PostMapping(value = "/admin/index/create", produces = "application/json")
    public Vector<Response> createIndex(@RequestParam String database, @RequestParam String schema,
                                        @RequestParam String field) {
        Vector<Response> responses = new Vector<>();
        try {
            indexManager.createIndex(database, schema, field);
            responses.add(new Response(ResponseType.SUCCESS, "Index created on " + schema + "." + field));
        } catch (Exception e) {
            responses.add(new Response(ResponseType.ERROR, e.getMessage()));
        }
        if (!isAdminOrigin()) {
            return responses;
        }
        for (Node node : Network.nodes) {
            responses.add(node.createIndex(database, schema, field));
        }
        return responses;
    }

    @PostMapping(value = "/admin/index/drop", produces = "application/json")
    public Vector<Response> dropIndex(@RequestParam String database, @RequestParam String schema,
                                      @RequestParam String field) {
        Vector<Response> responses = new Vector<>();
        try {
            indexManager.dropIndex(database, schema, field);
            responses.add(new Response(ResponseType.SUCCESS, "Index dropped on " + schema + "." + field));
        } catch (Exception e) {
            responses.add(new Response(ResponseType.ERROR, e.getMessage()));
        }
        if (!isAdminOrigin()) {
            return responses;
        }
        for (Node node : Network.nodes) {
            responses.add(node.dropIndex(database, schema, field));
        }
        return responses;
    }

    @GetMapping(value = "/admin/index/list", produces = "application/json")
    public Vector<Response> listIndexes(@RequestParam String database, @RequestParam String schema) {
        try {
            List<String> fields = indexManager.listIndexes(database, schema);
            return one(new Response(ResponseType.SUCCESS, "Indexed fields", fields));
        } catch (Exception e) {
            return one(new Response(ResponseType.ERROR, e.getMessage()));
        }
    }

    @PostMapping(value = "/user/index/query", produces = "application/json")
    public Response query(@RequestBody HashMap<String, Object> body) {
        try {
            String database = (String) body.get("database");
            String schema = (String) body.get("schema");
            String field = (String) body.get("field");
            Object opRaw = body.get("op");
            if (database == null || schema == null || field == null || opRaw == null) {
                return new Response(ResponseType.ERROR, "Missing required field (database, schema, field, op)");
            }
            BPlusTree.Op op = BPlusTree.Op.valueOf(opRaw.toString().toUpperCase());
            Object value = body.get("value");
            Object high = body.get("high");
            boolean ascending = !"DESC".equalsIgnoreCase(String.valueOf(body.getOrDefault("order", "ASC")));
            Object rawLimit = body.get("limit");
            int limit = (rawLimit instanceof Number) ? ((Number) rawLimit).intValue() : -1;
            Object rawOffset = body.get("offset");
            int offset = (rawOffset instanceof Number) ? ((Number) rawOffset).intValue() : 0;
            List<Integer> ids = indexManager.query(database, schema, field, op, value, high, ascending, offset, limit);
            return new Response(ResponseType.SUCCESS, "Query results", ids);
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
