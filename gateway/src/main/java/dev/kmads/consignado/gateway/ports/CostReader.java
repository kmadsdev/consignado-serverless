package dev.kmads.consignado.gateway.ports;

import java.time.Instant;
import java.util.Optional;

public interface CostReader {
    /** amount: in currency, the billing currency of the subscription (ISO 4217, for example BRL). Null currency: unknown. */
    record Cost(double amount, String currency, Instant asOf) {}

    /** Month-to-date cost of the resource group. Empty when the cost API does not answer. Never throws. */
    Optional<Cost> monthToDate();
}
