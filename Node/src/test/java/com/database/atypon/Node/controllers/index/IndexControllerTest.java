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
import java.util.List;
import java.util.Vector;

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
        return new IndexController(new IndexManager(root),
                new AuthenticationService(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()));
    }

    @Test
    void createRejectsNonAdmin(@TempDir Path root) throws Exception {
        IndexController c = controllerOn(root);
        // "user" token is not admin -> error, no index created
        assertThat(c.createIndex("shop", "users", "Age", "user").get(0).getResponseType())
                .isEqualTo(ResponseType.ERROR);
        // and nothing was created
        assertThat(c.listIndexes("shop", "users", "internal").get(0).getContent()).asList().isEmpty();
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
        assertThat(r.getContent()).asList().containsExactly(1, 0); // docIds for Age>=25, ascending by Age
    }

    @Test
    void createThenDrop(@TempDir Path root) throws Exception {
        IndexController c = controllerOn(root);
        assertThat(c.createIndex("shop", "users", "Age", "internal").get(0).getResponseType())
                .isEqualTo(ResponseType.SUCCESS);
        Vector<Response> listed = c.listIndexes("shop", "users", "internal");
        assertThat(listed.get(0).getResponseType()).isEqualTo(ResponseType.SUCCESS);
        assertThat(listed.get(0).getContent()).asList().contains("Age");

        assertThat(c.dropIndex("shop", "users", "Age", "internal").get(0).getResponseType())
                .isEqualTo(ResponseType.SUCCESS);
        Vector<Response> after = c.listIndexes("shop", "users", "internal");
        assertThat(after.get(0).getResponseType()).isEqualTo(ResponseType.SUCCESS);
        assertThat(after.get(0).getContent()).asList().doesNotContain("Age");
    }

    @Test
    void dropRejectsNonAdmin(@TempDir Path root) throws Exception {
        IndexController c = controllerOn(root);
        assertThat(c.dropIndex("shop", "users", "Age", "user").get(0).getResponseType())
                .isEqualTo(ResponseType.ERROR);
    }

    @Test
    void descendingAndUnknownOpAndPagination(@TempDir Path root) throws Exception {
        IndexController c = controllerOn(root);
        assertThat(c.createIndex("shop", "users", "Age", "internal").get(0).getResponseType())
                .isEqualTo(ResponseType.SUCCESS);

        // DESC: Age>=25 -> doc0 (30) then doc1 (25)
        HashMap<String, Object> desc = query("GTE", 25);
        desc.put("order", "DESC");
        Response d = c.query(desc, "user");
        assertThat(d.getResponseType()).isEqualTo(ResponseType.SUCCESS);
        assertThat(d.getContent()).asList().containsExactly(0, 1);

        // unknown op -> ERROR response (IllegalArgumentException caught)
        assertThat(c.query(query("NOPE", 25), "user").getResponseType()).isEqualTo(ResponseType.ERROR);

        // missing required field (op) -> ERROR response
        HashMap<String, Object> noOp = query("GTE", 25);
        noOp.remove("op");
        assertThat(c.query(noOp, "user").getResponseType()).isEqualTo(ResponseType.ERROR);

        // pagination: GTE 0 ASC -> [1, 0]; offset 1, limit 1 -> [0]
        HashMap<String, Object> page = query("GTE", 0);
        page.put("order", "ASC");
        page.put("offset", 1);
        page.put("limit", 1);
        Response p = c.query(page, "user");
        assertThat(p.getResponseType()).isEqualTo(ResponseType.SUCCESS);
        assertThat(p.getContent()).asList().containsExactly(0);
    }

    private static HashMap<String, Object> query(String op, Object value) {
        HashMap<String, Object> q = new HashMap<>();
        q.put("database", "shop"); q.put("schema", "users"); q.put("field", "Age");
        q.put("op", op); q.put("value", value);
        return q;
    }

    @Test
    void queryRejectsNonUser(@TempDir Path root) throws Exception {
        IndexController c = controllerOn(root);
        HashMap<String, Object> q = new HashMap<>();
        q.put("database", "shop"); q.put("schema", "users"); q.put("field", "Age"); q.put("op", "EQ"); q.put("value", 30);
        assertThat(c.query(q, "").getResponseType()).isEqualTo(ResponseType.ERROR);
    }
}
