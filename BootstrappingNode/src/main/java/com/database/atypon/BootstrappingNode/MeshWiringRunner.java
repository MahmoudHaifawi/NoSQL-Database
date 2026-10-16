package com.database.atypon.BootstrappingNode;

import com.database.atypon.BootstrappingNode.model.Node;
import com.database.atypon.BootstrappingNode.security.ServiceTokens;
import com.database.atypon.BootstrappingNode.utils.ClusterNodes;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Vector;

/**
 * On start-up (coordinator role only), wires every data node's peer list and self-assignment.
 * A node that is not reachable yet is skipped; it is wired when it comes up and re-registers.
 */
@Component
@Profile("coordinator")
public class MeshWiringRunner implements CommandLineRunner {

    @Override
    public void run(String... args) {
        RestTemplate restTemplate = new RestTemplate();
        List<Node> nodes = ClusterNodes.all();

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", ServiceTokens.bearer());

        for (int i = 0; i < nodes.size(); i++) {
            List<Node> peers = new Vector<>();
            for (int j = 0; j < nodes.size(); j++) {
                if (i != j) {
                    peers.add(nodes.get(j));
                }
            }
            String base = nodes.get(i).getURL();
            try {
                restTemplate.postForObject(base + "/network/add/nodes", new HttpEntity<>(peers, headers), String.class);
                restTemplate.postForObject(base + "/network/assign/self", new HttpEntity<>(nodes.get(i), headers), String.class);
            } catch (Exception e) {
                // Node not reachable yet; wired when it comes up and re-registers.
            }
        }
    }
}
