package com.database.atypon.DBMS.database_system.connection;

import com.database.atypon.DBMS.database_system.node.Node;
import com.database.atypon.DBMS.model.User;
import org.springframework.web.client.RestTemplate;
import java.util.LinkedHashMap;

public class

ConnectionRequest {

    /**
     * Base URL of the bootstrapping node. Defaults to the local-development address and can be
     * overridden with the {@code BOOTSTRAP_URL} environment variable (e.g. inside Docker, where
     * the bootstrapping node is reachable by its service name: {@code http://bootstrappingnode:8080}).
     */
    private static final String boostStrappingNodeURL =
            System.getenv().getOrDefault("BOOTSTRAP_URL", "http://localhost:8079");

    public static String createNewUser(User user, String token){
        try{
            RestTemplate restTemplate = new RestTemplate();
            String url = boostStrappingNodeURL + "/createNewUser";
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            if (token != null)
                headers.set("Authorization", "Bearer " + token);
            org.springframework.http.HttpEntity<User> request =
                    new org.springframework.http.HttpEntity<>(user, headers);
            String nodeURL = (restTemplate.postForObject(url, request, Node.class)).getURL();
            return nodeURL;
        }catch (Exception e){
            System.out.println(e.getMessage());
            return null;
        }
    }

    public static String login(User user, String nodeURL) throws Exception {
        if(user == null || nodeURL == null)
            throw new Exception("Invalid user or nodeURL");

        RestTemplate restTemplate = new RestTemplate();
        String url = nodeURL + "/login";
        LinkedHashMap<String, String> response = restTemplate.postForObject(url, user, LinkedHashMap.class);
        if(response.get("responseType").equals("ERROR"))
            throw new Exception(response.get("message"));
        return response.get("content");
    }
    public static String retrieveNodeURL(User user) throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        String url = boostStrappingNodeURL + "/getUserNode";
        try{
            // The bootstrapping node returns the node's full URL (http://host:port). Using it as-is
            // keeps gateway addressing consistent with the host names the cluster resolves peers by,
            // whether that host is "localhost" (local run) or a container name (Docker).
            return restTemplate.postForObject(url, user, String.class);
        }catch (Exception e){
            System.out.println(e.getMessage());
            throw new Exception("User not found");
        }
    }

}

