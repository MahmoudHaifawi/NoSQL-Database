package com.database.atypon.BootstrappingNode.utils;

import com.database.atypon.BootstrappingNode.model.Node;
import com.database.atypon.BootstrappingNode.model.NodeStatus;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the parallel health probe: a node answering {@code /health} is reported up, a node whose
 * port has nothing listening is reported down, and each node's identity/url is carried through.
 */
class ClusterStatusServiceTest {

    @Test
    void marksReachableNodeUpAndUnreachableNodeDown() throws IOException {
        // A live stub node that answers /health.
        HttpServer live = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        live.createContext("/health", exchange -> {
            byte[] body = "UP".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        live.start();
        String livePort = String.valueOf(live.getAddress().getPort());

        // A port with nothing listening -> connection refused -> reported down.
        int deadPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }

        try {
            List<NodeStatus> statuses = new ClusterStatusService().status(List.of(
                    new Node("localhost", livePort),
                    new Node("localhost", String.valueOf(deadPort))));

            assertThat(statuses).hasSize(2);
            NodeStatus up = statuses.get(0);
            assertThat(up.isUp()).isTrue();
            assertThat(up.getName()).isEqualTo("localhost");
            assertThat(up.getPort()).isEqualTo(livePort);
            assertThat(up.getUrl()).isEqualTo("http://localhost:" + livePort);

            assertThat(statuses.get(1).isUp()).isFalse();
        } finally {
            live.stop(0);
        }
    }
}
