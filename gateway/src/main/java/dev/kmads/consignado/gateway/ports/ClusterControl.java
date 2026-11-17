package dev.kmads.consignado.gateway.ports;

import dev.kmads.consignado.gateway.core.PowerState;

/** The only three things the gateway may do with the cluster: read, start, stop. */
public interface ClusterControl {
    /** Never throws. Returns PowerState.UNKNOWN when ARM does not answer. */
    PowerState powerState();
    /** True when ARM accepted the start. Never throws. */
    boolean start();
    /** True when ARM accepted the stop. Never throws. */
    boolean stop();
}
