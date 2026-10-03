package com.database.atypon.BootstrappingNode.utils;

import com.database.atypon.BootstrappingNode.model.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Single source of truth for the cluster topology the bootstrapping node knows about.
 *
 * <p>The node list is read once from the {@code CLUSTER_NODES} environment variable, a
 * comma-separated list of {@code host:port} entries. This lets the same build run in two
 * shapes without code changes:
 * <ul>
 *   <li>Local development (default): {@code localhost:8080} — a single node, so every user
 *       routes to it and peer wiring is a no-op.</li>
 *   <li>Docker / Kubernetes: e.g. {@code CLUSTER_NODES=Node0:8080,Node1:8080,Node2:8080},
 *       where each host is a container/service name resolvable on the cluster network.</li>
 * </ul>
 *
 * <p>Both the user load balancer and the start-up mesh wiring read from here, so the host
 * names they hand out are always consistent (an earlier version disagreed on casing —
 * {@code node0} vs {@code Node0} — which broke DNS resolution inside Docker).
 */
public final class ClusterNodes {

    private static final String DEFAULT_SPEC = "localhost:8080";
    private static final String DEFAULT_PORT = "8080";

    private static final List<Node> NODES =
            Collections.unmodifiableList(parse(resolveSpec()));

    private ClusterNodes() {
    }

    private static String resolveSpec() {
        String env = System.getenv("CLUSTER_NODES");
        if (env == null || env.trim().isEmpty()) {
            return DEFAULT_SPEC;
        }
        return env;
    }

    /** Parses a {@code host:port,host:port} spec into nodes. Package-private for testing. */
    static List<Node> parse(String spec) {
        List<Node> nodes = new ArrayList<>();
        for (String raw : spec.split(",")) {
            String entry = raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int sep = entry.lastIndexOf(':');
            String host = (sep < 0) ? entry : entry.substring(0, sep);
            String port = (sep < 0) ? DEFAULT_PORT : entry.substring(sep + 1).trim();
            if (port.isEmpty()) {
                port = DEFAULT_PORT;
            }
            nodes.add(new Node(host.trim(), port));
        }
        if (nodes.isEmpty()) {
            nodes.add(new Node("localhost", DEFAULT_PORT));
        }
        return nodes;
    }

    /** The configured nodes, in declaration order. Never empty. */
    public static List<Node> all() {
        return NODES;
    }

    public static int count() {
        return NODES.size();
    }

    public static Node at(int index) {
        List<Node> nodes = NODES;
        return nodes.get(Math.floorMod(index, nodes.size()));
    }
}
