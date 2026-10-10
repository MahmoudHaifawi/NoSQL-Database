package com.database.atypon.BootstrappingNode.model;

/**
 * A data node's identity plus a live reachability flag, as reported by {@code GET /cluster}.
 * Serialized to JSON for the gateway's cluster-topology view.
 */
public class NodeStatus {

    private final String name;
    private final String port;
    private final String url;
    private final boolean up;

    public NodeStatus(String name, String port, String url, boolean up) {
        this.name = name;
        this.port = port;
        this.url = url;
        this.up = up;
    }

    public String getName() {
        return name;
    }

    public String getPort() {
        return port;
    }

    public String getUrl() {
        return url;
    }

    public boolean isUp() {
        return up;
    }
}
