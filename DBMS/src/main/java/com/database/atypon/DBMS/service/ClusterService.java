package com.database.atypon.DBMS.service;

import com.database.atypon.DBMS.database_system.cluster.ClusterRequest;
import com.database.atypon.DBMS.database_system.cluster.NodeStatus;
import com.database.atypon.DBMS.database_system.connection.ConnectionRequest;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * Fetches the cluster topology from the bootstrapping node and marks the node this session is pinned
 * to (the one matching the session's {@code nodeURL}), so the dashboard can highlight "your" node.
 */
@Service
public class ClusterService {

    public List<NodeStatus> topology(String token, String currentNodeURL) throws Exception {
        NodeStatus[] nodes = ClusterRequest.topology(ConnectionRequest.bootstrapUrl(), token);
        List<NodeStatus> list = Arrays.asList(nodes);
        for (NodeStatus node : list) {
            node.setCurrent(currentNodeURL != null && currentNodeURL.equals(node.getUrl()));
        }
        return list;
    }
}
