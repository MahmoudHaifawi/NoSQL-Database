package com.database.atypon.BootstrappingNode.utils;

import com.database.atypon.BootstrappingNode.model.Node;
import com.database.atypon.BootstrappingNode.model.NodeStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reports the live status of every data node the bootstrapping node knows about ({@link ClusterNodes}).
 *
 * <p>Each node's unauthenticated {@code /health} probe is issued in parallel with a short timeout, so
 * a slow or dead node delays the whole report by at most that timeout rather than summing across the
 * cluster. Powers the gateway dashboard's topology panel.
 */
@Service
public class ClusterStatusService {

    private static final int TIMEOUT_MS = 400;

    private final RestTemplate probe;

    public ClusterStatusService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT_MS);
        factory.setReadTimeout(TIMEOUT_MS);
        this.probe = new RestTemplate(factory);
    }

    /** Status of the configured cluster. */
    public List<NodeStatus> status() {
        return status(ClusterNodes.all());
    }

    /** Status of the given nodes. Package-visible overload so the probe can be tested against stubs. */
    List<NodeStatus> status(List<Node> nodes) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(nodes.size(), 8)));
        try {
            List<CompletableFuture<NodeStatus>> futures = new ArrayList<>();
            for (Node node : nodes) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> new NodeStatus(node.getName(), node.getPort(), node.getURL(), isUp(node.getURL())),
                        pool));
            }
            List<NodeStatus> result = new ArrayList<>();
            for (CompletableFuture<NodeStatus> future : futures) {
                result.add(future.join());
            }
            return result;
        } finally {
            pool.shutdown();
        }
    }

    /** True if the node answers its {@code /health} probe within the timeout. */
    private boolean isUp(String nodeUrl) {
        try {
            probe.getForObject(nodeUrl + "/health", String.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
