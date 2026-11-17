package dev.kmads.consignado.gateway.ports;

import dev.kmads.consignado.gateway.core.ClusterView;

public interface ClusterObserver {
    /** Reads nodes, pods and endpoints of namespace consignado. Never throws: ClusterView.DOWN on failure. */
    ClusterView observe();
}
