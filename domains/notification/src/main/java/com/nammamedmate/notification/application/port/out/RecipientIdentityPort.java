package com.nammamedmate.notification.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Cross-table identity lookup for preference gating. Default stub returns empty; JDBC adapter
 * queries {@code customers}. Apps may override.
 */
public interface RecipientIdentityPort {

  Optional<UUID> findCustomerIdByPhone(String phone);

  Optional<String> findPhoneByCustomerId(UUID customerId);
}
