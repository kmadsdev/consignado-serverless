package dev.kmads.consignado.gateway.ports;

import dev.kmads.consignado.gateway.core.User;
import java.util.List;
import java.util.Optional;

public interface UserStore {
    Optional<User> find(String id);
    void save(User user);
    List<User> list();
}
