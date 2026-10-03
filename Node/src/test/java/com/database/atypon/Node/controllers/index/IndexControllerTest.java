package com.database.atypon.Node.controllers.index;

import com.database.atypon.Node.services.authentication.AuthenticationService;
import com.database.atypon.Node.services.index.IndexManager;
import com.database.atypon.Node.utils.response.Response;
import com.database.atypon.Node.utils.response.ResponseType;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

class IndexControllerTest {

    private IndexController controllerOn(Path root) throws IOException {
        Path schemas = root.resolve("shop").resolve("schemas");
        Files.createDirectories(schemas);
        Files.writeString(schemas.resolve("users.json"),
                new JSONObject().put("info", new JSONObject().put("schemaName", "users"))
                        .put("schema", new JSONObject().put("Age", "Integer")).toString());
        Path recs = root.resolve("shop").resolve("users-records");
        Files.createDirectories(recs);
        Files.writeString(recs.resolve("0.json"), new JSONObject().put("Age", 30).toString());
        Files.writeString(recs.resolve("1.json"), new JSONObject().put("Age", 25).toString());
        return new IndexController(new IndexManager(root), new AuthenticationService());
    }

    @Test
    void createRejectsNonAdmin(@TempDir Path root) throws Exception {
        IndexController c = controllerOn(root);
        // "user" token is not admin -> error, no index created
        assertThat(c.createIndex("shop", "users", "Age", "user").get(0).getResponseType())
                .isEqualTo(ResponseType.ERROR);
    }

    @Test
    void createListQueryWithInternalToken(@TempDir Path root) throws Exception {
        IndexController c = controllerOn(root);
        // "internal" is admin-authorized but not ADMIN, so no broadcast (Network.nodes untouched)
        assertThat(c.createIndex("shop", "users", "Age", "internal").get(0).getResponseType())
                .isEqualTo(ResponseType.SUCCESS);
        assertThat(c.listIndexes("shop", "users", "internal").get(0).getResponseType())
                .isEqualTo(ResponseType.SUCCESS);

        HashMap<String, Object> q = new HashMap<>();
        q.put("database", "shop"); q.put("schema", "users"); q.put("field", "Age");
        q.put("op", "GTE"); q.put("value", 25); q.put("order", "ASC"); q.put("limit", -1); q.put("offset", 0);
        Response r = c.query(q, "user");
        assertThat(r.getResponseType()).isEqualTo(ResponseType.SUCCESS);
        assertThat(r.getContent().toString()).contains("1").contains("0"); // docIds for Age>=25
    }

    @Test
    void queryRejectsNonUser(@TempDir Path root) throws Exception {
        IndexController c = controllerOn(root);
        HashMap<String, Object> q = new HashMap<>();
        q.put("database", "shop"); q.put("schema", "users"); q.put("field", "Age"); q.put("op", "EQ"); q.put("value", 30);
        assertThat(c.query(q, "").getResponseType()).isEqualTo(ResponseType.ERROR);
    }
}
