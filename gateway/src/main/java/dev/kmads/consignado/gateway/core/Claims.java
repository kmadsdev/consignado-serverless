package dev.kmads.consignado.gateway.core;

import java.util.Set;

/** The parts of a verified Entra ID access token that the gateway uses. */
public record Claims(String oid, String email, String name, Set<String> scopes) {}
