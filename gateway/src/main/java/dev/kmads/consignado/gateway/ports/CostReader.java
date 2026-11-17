package dev.kmads.consignado.gateway.ports;

import java.time.Instant;
import java.util.Optional;

public interface CostReader {
    record Cost(double usd, Instant asOf) {}

    /** Month-to-date cost of the resource group. Empty when the cost API does not answer. Never throws. */
    Optional<Cost> monthToDate();
}
