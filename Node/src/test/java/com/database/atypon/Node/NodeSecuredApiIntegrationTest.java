package com.database.atypon.Node;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of the data node over real HTTP with Spring Security active: it logs in to obtain
 * a JWT, then drives the whole secured lifecycle — create database, schema, insert, read, index,
 * query, update, delete — asserting the real responses, and that a request without a token is
 * rejected. Exercises the full stack (security filter -> controllers -> services -> B+-tree + disk).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NodeSecuredApiIntegrationTest {

    private static final Path INFO = Paths.get("./data/info.json");
    private static final String DB = "ittest";
    private static byte[] originalInfo;

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @BeforeAll
    void seedAdmin() throws Exception {
        Files.createDirectories(INFO.getParent());
        originalInfo = Files.exists(INFO) ? Files.readAllBytes(INFO) : null;
        String hash = new BCryptPasswordEncoder().encode("admin");
        Files.writeString(INFO, new JSONObject()
                .put("databases", new JSONArray())
                .put("users", new JSONObject().put("mahmoud", new JSONObject()
                        .put("username", "mahmoud").put("role", "admin").put("password", hash)))
                .toString());
    }

    @AfterAll
    void cleanup() throws Exception {
        if (originalInfo != null) Files.write(INFO, originalInfo);
        Path db = Paths.get("./data/" + DB);
        if (Files.exists(db)) {
            try (var paths = Files.walk(db)) {
                paths.sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete());
            }
        }
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private ResponseEntity<String> post(String path, String token, String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.set("Authorization", token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(base() + path, HttpMethod.POST,
                new HttpEntity<>(jsonBody == null ? "" : jsonBody, headers), String.class);
    }

    @Test
    void fullSecuredLifecycle() {
        // 1. login -> JWT
        ResponseEntity<String> login = post("/login", null, "{\"username\":\"mahmoud\",\"password\":\"admin\"}");
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String jwt = new JSONObject(login.getBody()).getString("content");
        assertThat(jwt.split("\\.")).hasSize(3); // header.payload.signature

        // 2. security is enforced: a protected endpoint without a token is rejected
        assertThat(post("/admin/database/create?databaseName=" + DB, null, "").getStatusCode().value())
                .isIn(401, 403);

        // 3. create database (admin)
        assertThat(firstType(post("/admin/database/create?databaseName=" + DB, jwt, ""))).isEqualTo("SUCCESS");

        // 4. create schema
        assertThat(firstType(post("/write/schema/new?database=" + DB, jwt,
                "{\"schemaName\":\"t\",\"schema\":{\"n\":\"Integer\"}}"))).isEqualTo("SUCCESS");

        // 5. insert a document (id 0)
        assertThat(firstType(post("/write/document/new?database=" + DB + "&schema=t", jwt,
                "{\"n\":7}"))).isEqualTo("SUCCESS");

        // 6. read it back
        JSONObject read = new JSONObject(post("/user/read/document?databaseName=" + DB + "&schemaName=t&id=0", jwt, "").getBody());
        assertThat(new JSONObject(read.getString("content")).getInt("n")).isEqualTo(7);

        // 7. create an index on n and query it
        assertThat(firstType(post("/admin/index/create?database=" + DB + "&schema=t&field=n", jwt, ""))).isEqualTo("SUCCESS");
        JSONObject query = new JSONObject(post("/user/index/query", jwt,
                "{\"database\":\"" + DB + "\",\"schema\":\"t\",\"field\":\"n\",\"op\":\"EQ\",\"value\":7}").getBody());
        assertThat(query.getJSONArray("content").toList()).containsExactly(0);

        // 8. update (optimistic) -> version 2, and the index reflects the new value
        JSONArray update = new JSONArray(post("/write/document/update?database=" + DB + "&schema=t&id=0", jwt,
                "{\"n\":8,\"_version\":1}").getBody());
        assertThat(update.getJSONObject(0).getString("responseType")).isEqualTo("SUCCESS");
        assertThat(update.getJSONObject(0).getInt("content")).isEqualTo(2);
        JSONObject q8 = new JSONObject(post("/user/index/query", jwt,
                "{\"database\":\"" + DB + "\",\"schema\":\"t\",\"field\":\"n\",\"op\":\"EQ\",\"value\":8}").getBody());
        assertThat(q8.getJSONArray("content").toList()).containsExactly(0);

        // 9. delete (version-checked) -> gone
        assertThat(firstType(post("/write/document/delete?database=" + DB + "&schema=t&id=0&version=2", jwt, ""))).isEqualTo("SUCCESS");
        JSONObject readGone = new JSONObject(post("/user/read/document?databaseName=" + DB + "&schemaName=t&id=0", jwt, "").getBody());
        assertThat(readGone.getString("responseType")).isEqualTo("ERROR");
    }

    /** First response type from an endpoint that returns a Vector&lt;Response&gt; (JSON array). */
    private static String firstType(ResponseEntity<String> response) {
        return new JSONArray(response.getBody()).getJSONObject(0).getString("responseType");
    }
}
