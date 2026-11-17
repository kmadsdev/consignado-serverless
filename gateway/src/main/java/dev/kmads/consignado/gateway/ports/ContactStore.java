package dev.kmads.consignado.gateway.ports;

import dev.kmads.consignado.gateway.core.Contact;
import java.util.List;

public interface ContactStore {
    void save(Contact contact);
    List<Contact> list();
    /** Removes the row. No error when it does not exist. */
    void delete(String id);
}
