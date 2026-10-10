package com.database.atypon.DBMS.security;

/** Authenticated gateway principal, carrying the node-issued JWT and the user's data-node URL. */
public class DbmsUser {

    private final String username;
    private final String role;
    private final String jwt;
    private final String nodeURL;

    public DbmsUser(String username, String role, String jwt, String nodeURL) {
        this.username = username;
        this.role = role;
        this.jwt = jwt;
        this.nodeURL = nodeURL;
    }

    public String getUsername() { return username; }
    public String getRole() { return role; }
    public String getJwt() { return jwt; }
    public String getNodeURL() { return nodeURL; }
}
