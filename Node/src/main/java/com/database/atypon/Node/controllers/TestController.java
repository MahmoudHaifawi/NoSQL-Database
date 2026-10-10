package com.database.atypon.Node.controllers;

import com.database.atypon.Node.model.Network;
import com.database.atypon.Node.model.Node;
import org.json.JSONObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

@RestController
public class TestController {

    private static File file = new File("./node_id.txt");
    private static int x = 0;
    @RequestMapping("/test")
    public String test() throws IOException {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("message", "Hello from node");
        jsonObject.put("node_id", ++x);
        try(FileWriter fw = new FileWriter(file, true);){
            BufferedWriter bw = new BufferedWriter(fw);
            bw.write(jsonObject.toString());
            bw.close();
        }
        return "test";
    }

    /**
     * Unauthenticated liveness probe. The bootstrapping node polls this on every data node to build
     * the cluster-topology view shown on the gateway dashboard, so it must be reachable without a
     * token (it exposes no data — only that this node is up).
     */
    @GetMapping("/health")
    public String health() {
        return "UP";
    }

    @RequestMapping("/test/broadcast")
    public String broadcast(@RequestBody String message){
        for (Node node : Network.nodes) {
            String url = "http://" + node.getName() + ":" + node.getPort() + "/write/test";
            RestTemplate restTemplate = new RestTemplate();
            restTemplate.postForObject(url, message, String.class);
        }
        return "Broadcasting your message";
    }
}

