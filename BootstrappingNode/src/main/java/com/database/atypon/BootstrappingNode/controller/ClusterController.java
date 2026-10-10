package com.database.atypon.BootstrappingNode.controller;

import com.database.atypon.BootstrappingNode.model.NodeStatus;
import com.database.atypon.BootstrappingNode.utils.ClusterStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Exposes the cluster topology the bootstrapping node knows about, with each data node's live
 * reachability. Any authenticated user may read it; the gateway forwards the logged-in user's JWT
 * and renders the result as the dashboard topology panel.
 */
@RestController
public class ClusterController {

    private final ClusterStatusService clusterStatusService;

    public ClusterController(ClusterStatusService clusterStatusService) {
        this.clusterStatusService = clusterStatusService;
    }

    @GetMapping("/cluster")
    public List<NodeStatus> cluster() {
        return clusterStatusService.status();
    }
}
