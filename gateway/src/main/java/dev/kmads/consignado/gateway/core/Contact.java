package dev.kmads.consignado.gateway.core;

/**
 * One row of the contacts table. Same shape as the frontend type ContactRequest, plus userId:
 * the Entra object id of a signed-in sender (null for a guest).
 */
public record Contact(String id, String kind, String name, String email, String message, String createdAt,
                      String userId) {}
