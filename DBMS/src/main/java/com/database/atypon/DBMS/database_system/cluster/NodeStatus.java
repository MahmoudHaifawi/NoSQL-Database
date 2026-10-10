package com.database.atypon.DBMS.database_system.cluster;

/**
 * A data node as reported by the bootstrapping node's {@code /cluster} endpoint, plus a
 * gateway-computed {@code current} flag marking the node this session is pinned to. Rendered as the
 * dashboard topology panel.
 */
public class NodeStatus {

    private String name;
    private String port;
    private String url;
    private boolean up;
    private boolean current;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPort() {
        return port;
    }

    public void setPort(String port) {
        this.port = port;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public boolean isUp() {
        return up;
    }

    public void setUp(boolean up) {
        this.up = up;
    }

    public boolean isCurrent() {
        return current;
    }

    public void setCurrent(boolean current) {
        this.current = current;
    }
}
