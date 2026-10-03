package com.database.atypon.BootstrappingNode;

import com.database.atypon.BootstrappingNode.model.Node;
import com.database.atypon.BootstrappingNode.model.User;
import com.database.atypon.BootstrappingNode.response.Response;
import com.sun.net.httpserver.Headers;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Vector;

@SpringBootApplication
public class BootstrappingNodeApplication implements CommandLineRunner {

	public static void main(String[] args) {
		SpringApplication.run(BootstrappingNodeApplication.class, args);
	}

	@Override
	public void run(String... args) {

		RestTemplate restTemplate = new RestTemplate();
		List<Node> nodes = com.database.atypon.BootstrappingNode.utils.ClusterNodes.all();

		for (int i = 0; i < nodes.size(); i++) {
			List<Node> peers = new Vector<>();
			for (int j = 0; j < nodes.size(); j++)
				if (i != j)
					peers.add(nodes.get(j));

			String base = nodes.get(i).getURL();
			try {
				restTemplate.postForObject(base + "/network/add/nodes", peers, String.class);
				restTemplate.postForObject(base + "/network/assign/self", nodes.get(i), String.class);
			} catch (Exception e) {
				// Node not reachable yet (e.g. started after the bootstrapper, or absent in a
				// smaller local topology). Peers are wired when the node comes up and re-registers.
			}
		}
	}
}
