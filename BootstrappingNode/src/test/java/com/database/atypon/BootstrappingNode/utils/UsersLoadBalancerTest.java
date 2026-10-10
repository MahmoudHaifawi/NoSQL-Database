package com.database.atypon.BootstrappingNode.utils;

import com.database.atypon.BootstrappingNode.model.Node;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the load balancer against the configured topology (the default single local node, since
 * CLUSTER_NODES is not set in the test environment). Verifies a user is routed to a configured node
 * and that the "current" node tracks the most recent assignment.
 */
class UsersLoadBalancerTest {

    @Test
    void routesToAConfiguredNode() {
        Node node = UsersLoadBalancer.getUserNode(ClusterNodes.count());
        assertThat(ClusterNodes.all()).extracting(Node::getURL).contains(node.getURL());
    }

    @Test
    void currentNodeMatchesTheMostRecentAssignment() {
        Node assigned = UsersLoadBalancer.getUserNode(ClusterNodes.count());
        Node current = UsersLoadBalancer.getCurrentUserNode(ClusterNodes.count());
        assertThat(current.getURL()).isEqualTo(assigned.getURL());
    }

    @Test
    void repeatedAssignmentsStayWithinTheConfiguredTopology() {
        for (int i = 0; i < 5; i++) {
            Node node = UsersLoadBalancer.getUserNode(ClusterNodes.count());
            assertThat(ClusterNodes.all()).extracting(Node::getURL).contains(node.getURL());
        }
    }
}
