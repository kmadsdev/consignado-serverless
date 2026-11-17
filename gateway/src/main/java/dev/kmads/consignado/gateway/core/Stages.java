package dev.kmads.consignado.gateway.core;

import java.util.List;

/** Stage ids of the contract (front/src/api/stages.ts). Do not change a name here. */
public final class Stages {
    private Stages() {}

    public static final String REQUEST = "request";
    public static final String EDGE = "edge";
    public static final String GATEWAY = "gateway";
    public static final String IDENTITY = "identity";
    public static final String QUOTA = "quota";
    public static final String KEYVAULT = "keyvault";
    public static final String ARM_START = "arm-start";
    public static final String CONTROL_PLANE = "control-plane";
    public static final String NODE = "node";
    public static final String IMAGE_PULL = "image-pull";
    public static final String SECRETS_MOUNT = "secrets-mount";
    public static final String CONTAINER_START = "container-start";
    public static final String DATABASE = "database";
    public static final String READINESS = "readiness";
    public static final String LOAD_BALANCER = "load-balancer";
    public static final String FIRST_RESPONSE = "first-response";
    public static final String WINDOW_OPEN = "window-open";
    public static final String WINDOW_CLOSE = "window-close";
    public static final String CLUSTER_STOP = "cluster-stop";
    public static final String FUNCTION_COLD_START = "function-cold-start";
    public static final String JVM_START = "jvm-start";
    public static final String RESPONSE = "response";
    /** Contract addendum 2: time inside the Spring Boot API, without its database time. */
    public static final String APP = "app";
    /** Contract addendum 2: the Functions host between the gateway and the application (network included). */
    public static final String FUNCTIONS_HOST = "functions-host";

    public static final List<String> AKS = List.of(
            REQUEST, EDGE, GATEWAY, IDENTITY, QUOTA, KEYVAULT, ARM_START, CONTROL_PLANE, NODE, IMAGE_PULL,
            SECRETS_MOUNT, CONTAINER_START, DATABASE, READINESS, LOAD_BALANCER, FIRST_RESPONSE, WINDOW_OPEN,
            WINDOW_CLOSE, CLUSTER_STOP);

    public static final List<String> FUNCTIONS = List.of(
            REQUEST, EDGE, GATEWAY, IDENTITY, KEYVAULT, FUNCTION_COLD_START, JVM_START, FUNCTIONS_HOST, APP,
            DATABASE, RESPONSE);

    /** The stages of one proxied AKS request, in order (a subset of AKS plus APP). */
    public static final List<String> AKS_REQUEST = List.of(
            REQUEST, EDGE, GATEWAY, IDENTITY, QUOTA, KEYVAULT, LOAD_BALANCER, APP, DATABASE, RESPONSE);
}
