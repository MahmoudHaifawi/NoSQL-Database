package com.database.atypon.DBMS.database_system;

import com.database.atypon.DBMS.database_system.cluster.ClusterRequest;
import com.database.atypon.DBMS.database_system.cluster.NodeStatus;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the gateway's HTTP contract with the bootstrapping node's {@code /cluster} endpoint:
 * a GET to {@code /cluster} carrying the Bearer token, and parsing the returned node-status array.
 */
class ClusterRequestHttpTest {

    private HttpServer server;
    private String baseUrl;
    private volatile String lastMethod;
    private volatile String lastUri;
    private volatile String lastAuth;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/cluster", exchange -> {
            lastMethod = exchange.getRequestMethod();
            lastUri = exchange.getRequestURI().toString();
            lastAuth = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] body = ("[{\"name\":\"Node0\",\"port\":\"8080\",\"url\":\"http://Node0:8080\",\"up\":true},"
                    + "{\"name\":\"Node1\",\"port\":\"8080\",\"url\":\"http://Node1:8080\",\"up\":false}]")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Test
    void topologyGetsClusterWithBearerAndParsesNodes() throws Exception {
        NodeStatus[] nodes = ClusterRequest.topology(baseUrl, "jwt-abc");

        assertThat(lastMethod).isEqualTo("GET");
        assertThat(lastUri).isEqualTo("/cluster");
        assertThat(lastAuth).isEqualTo("Bearer jwt-abc");

        assertThat(nodes).hasSize(2);
        assertThat(nodes[0].getName()).isEqualTo("Node0");
        assertThat(nodes[0].getPort()).isEqualTo("8080");
        assertThat(nodes[0].getUrl()).isEqualTo("http://Node0:8080");
        assertThat(nodes[0].isUp()).isTrue();
        assertThat(nodes[1].isUp()).isFalse();
    }
}
