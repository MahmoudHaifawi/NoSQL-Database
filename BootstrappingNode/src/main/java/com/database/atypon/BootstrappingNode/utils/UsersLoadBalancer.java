package com.database.atypon.BootstrappingNode.utils;

import com.database.atypon.BootstrappingNode.model.Node;

/**
 * Round-robins users across the configured cluster {@link ClusterNodes}.
 *
 * <p>{@link #getUserNode(int)} picks the node a newly created user is placed on and advances
 * the cursor; {@link #getCurrentUserNode(int)} returns the node the most recently created
 * user was placed on (used when that user logs in). Both read from {@link ClusterNodes}, so
 * the host names are consistent across creation and routing. With a single configured node
 * (the local-development default) every user resolves to that one node.
 *
 * <p>The {@code numberOfNodes} argument is retained for call-site compatibility but is
 * superseded by the configured topology size.
 */
public final class UsersLoadBalancer {

    private static int usersCount = 0;

    private UsersLoadBalancer() {
    }

    public static synchronized Node getUserNode(int numberOfNodes) {
        Node node = ClusterNodes.at(usersCount);
        usersCount++;
        return node;
    }

    public static synchronized Node getCurrentUserNode(int numberOfNodes) {
        int lastAssigned = (usersCount == 0) ? 0 : usersCount - 1;
        return ClusterNodes.at(lastAssigned);
    }
}
