package com.database.atypon.DBMS.database_system;

import com.database.atypon.DBMS.database_system.index.IndexRequest;
import com.database.atypon.DBMS.database_system.read.ReadRequest;
import com.database.atypon.DBMS.database_system.write.WriteRequest;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the gateway's HTTP contract with a data node: each forwarder is pointed at a tiny
 * in-process stub server (JDK {@link HttpServer}) that records the request it receives and returns a
 * canned node response. Asserts the exact path/query, the Authorization header, the request body,
 * and how the forwarder parses the node's reply. No production code is changed and no real node is
 * needed; the Node integration test proves the node actually produces these shapes.
 */
class DbmsForwarderHttpTest {

    private HttpServer server;
    private String baseUrl;

    // Recorded from the last request the stub received.
    private volatile String lastMethod;
    private volatile String lastUri;
    private volatile String lastAuth;
    private volatile String lastBody;

    // Canned reply for the next request.
    private volatile String cannedBody = "{}";

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            lastMethod = exchange.getRequestMethod();
            lastUri = exchange.getRequestURI().toString();
            lastAuth = exchange.getRequestHeaders().getFirst("Authorization");
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] resp = cannedBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    // ---- WriteRequest ----

    @Test
    void createDocumentPostsCorrectPathBodyAndToken() {
        cannedBody = "[{\"responseType\":\"SUCCESS\",\"message\":\"Document created\"}]";
        String result = WriteRequest.createDocument("shop", "products",
                new JSONObject().put("n", 7), "jwt-abc", baseUrl);

        assertThat(lastMethod).isEqualTo("POST");
        assertThat(lastUri).isEqualTo("/write/document/new?database=shop&schema=products");
        assertThat(lastAuth).isEqualTo("jwt-abc");
        assertThat(new JSONObject(lastBody).getInt("n")).isEqualTo(7);
        assertThat(result).contains("Document created");
    }

    @Test
    void createSchemaPostsCorrectPath() {
        cannedBody = "[{\"responseType\":\"SUCCESS\",\"message\":\"ok\"}]";
        WriteRequest.createSchema("shop", new JSONObject().put("schemaName", "t"), "jwt", baseUrl);
        assertThat(lastUri).isEqualTo("/write/schema/new?database=shop");
    }

    @Test
    void updateDocumentPostsVersionedBody() {
        cannedBody = "[{\"responseType\":\"SUCCESS\",\"content\":2}]";
        WriteRequest.updateDocument("shop", "products", "0",
                new JSONObject().put("n", 8).put("_version", 1), "jwt", baseUrl);
        assertThat(lastUri).isEqualTo("/write/document/update?database=shop&schema=products&id=0");
        assertThat(new JSONObject(lastBody).getInt("_version")).isEqualTo(1);
    }

    @Test
    void deleteDocumentPostsPathWithVersionAndNoBody() {
        cannedBody = "[{\"responseType\":\"SUCCESS\"}]";
        WriteRequest.deleteDocument("shop", "products", "0", 3, "jwt", baseUrl);
        assertThat(lastUri).isEqualTo("/write/document/delete?database=shop&schema=products&id=0&version=3");
        assertThat(lastBody).isEmpty();
    }

    // ---- ReadRequest ----

    @Test
    void readAllUnwrapsContent() {
        cannedBody = "{\"content\":\"{\\\"0.json\\\":\\\"{\\\\\\\"n\\\\\\\":7}\\\"}\"}";
        JSONObject docs = ReadRequest.readAll("shop", "products", "jwt", baseUrl);
        assertThat(lastUri).isEqualTo("/user/read/all?databaseName=shop&schemaName=products");
        assertThat(docs.has("0.json")).isTrue();
    }

    @Test
    void readByIdUnwrapsContent() {
        cannedBody = "{\"content\":\"{\\\"n\\\":7}\"}";
        JSONObject doc = ReadRequest.readById("shop", "products", "0", "jwt", baseUrl);
        assertThat(lastUri).isEqualTo("/user/read/document?databaseName=shop&schemaName=products&id=0");
        assertThat(doc.getInt("n")).isEqualTo(7);
    }

    // ---- IndexRequest ----

    @Test
    void createIndexReturnsMessageOnSuccess() throws Exception {
        cannedBody = "[{\"responseType\":\"SUCCESS\",\"message\":\"Index created on products.n\"}]";
        String msg = IndexRequest.createIndex("shop", "products", "n", "jwt", baseUrl);
        assertThat(lastUri).isEqualTo("/admin/index/create?database=shop&schema=products&field=n");
        assertThat(msg).isEqualTo("Index created on products.n");
    }

    @Test
    void createIndexThrowsOnErrorResponse() {
        cannedBody = "[{\"responseType\":\"ERROR\",\"message\":\"index already exists\"}]";
        assertThatThrownBy(() -> IndexRequest.createIndex("shop", "products", "n", "jwt", baseUrl))
                .hasMessageContaining("already exists");
    }

    @Test
    void queryIdsParsesContentListInOrder() throws Exception {
        cannedBody = "{\"responseType\":\"SUCCESS\",\"content\":[1,0,4]}";
        Map<String, Object> body = new HashMap<>();
        body.put("database", "shop");
        body.put("schema", "products");
        body.put("field", "n");
        body.put("op", "GTE");
        List<Integer> ids = IndexRequest.queryIds(body, "jwt", baseUrl);
        assertThat(lastUri).isEqualTo("/user/index/query");
        assertThat(ids).containsExactly(1, 0, 4);
    }

    @Test
    void queryIdsThrowsOnErrorResponse() {
        cannedBody = "{\"responseType\":\"ERROR\",\"message\":\"no index on products.n\"}";
        Map<String, Object> body = new HashMap<>();
        body.put("database", "shop");
        assertThatThrownBy(() -> IndexRequest.queryIds(body, "jwt", baseUrl))
                .hasMessageContaining("no index");
    }
}
