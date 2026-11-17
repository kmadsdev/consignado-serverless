package dev.kmads.consignado.gateway.ports;

import java.time.LocalDate;

/** The retention side of the audit table. The write side is AuditLog. */
public interface AuditStore {
    /** Deletes every audit row of a day before the given day. Returns the count. Never throws: 0 on failure. */
    int deleteBefore(LocalDate day);
}
