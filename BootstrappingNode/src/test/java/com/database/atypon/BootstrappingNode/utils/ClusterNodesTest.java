package com.database.atypon.BootstrappingNode.utils;

import com.database.atypon.BootstrappingNode.model.Node;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterNodesTest {

    @Test
    void parsesSingleHostPort() {
        List<Node> nodes = ClusterNodes.parse("localhost:8080");
        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).getName()).isEqualTo("localhost");
        assertThat(nodes.get(0).getPort()).isEqualTo("8080");
        assertThat(nodes.get(0).getURL()).isEqualTo("http://localhost:8080");
    }

    @Test
    void parsesMultipleCommaSeparatedNodes() {
        List<Node> nodes = ClusterNodes.parse("Node0:8080,Node1:8080,Node2:8080");
        assertThat(nodes).extracting(Node::getURL)
                .containsExactly("http://Node0:8080", "http://Node1:8080", "http://Node2:8080");
    }

    @Test
    void trimsWhitespaceAndSkipsBlankEntries() {
        List<Node> nodes = ClusterNodes.parse(" Node0:8080 , , Node1:8080 ,");
        assertThat(nodes).extracting(Node::getURL)
                .containsExactly("http://Node0:8080", "http://Node1:8080");
    }

    @Test
    void defaultsPortWhenMissing() {
        List<Node> nodes = ClusterNodes.parse("onlyhost");
        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).getPort()).isEqualTo("8080");
        assertThat(nodes.get(0).getURL()).isEqualTo("http://onlyhost:8080");
    }

    @Test
    void emptySpecFallsBackToLocalhost() {
        List<Node> nodes = ClusterNodes.parse("   ");
        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).getURL()).isEqualTo("http://localhost:8080");
    }

    @Test
    void defaultTopologyIsNonEmptyAndAtWrapsWithFloorMod() {
        // The default (no CLUSTER_NODES env) is a single local node; at() must wrap any index.
        assertThat(ClusterNodes.count()).isGreaterThanOrEqualTo(1);
        Node first = ClusterNodes.at(0);
        assertThat(ClusterNodes.at(ClusterNodes.count())).usingRecursiveComparison().isEqualTo(first);
        assertThat(ClusterNodes.at(-1)).isNotNull(); // floorMod handles negatives without throwing
        assertThat(ClusterNodes.at(999)).isNotNull();
    }
}
