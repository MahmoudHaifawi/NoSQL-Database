package com.database.atypon.DBMS.database_system.cluster;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * Forwards the cluster-topology read from the gateway to the bootstrapping node's {@code /cluster}
 * endpoint. Mirrors the other forwarders: takes the target base URL (so it can be pointed at a stub
 * in tests) and sends the logged-in user's JWT as a Bearer token.
 */
public class ClusterRequest {

    public static NodeStatus[] topology(String bootstrapUrl, String token) throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.set("Authorization", "Bearer " + token);
        }
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<NodeStatus[]> response =
                restTemplate.exchange(bootstrapUrl + "/cluster", HttpMethod.GET, request, NodeStatus[].class);
        NodeStatus[] body = response.getBody();
        return body == null ? new NodeStatus[0] : body;
    }
}
