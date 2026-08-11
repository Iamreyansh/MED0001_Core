package com.nammamedmate.notification.application.port.out;

import com.nammamedmate.notification.domain.CustomerNotificationPreferences;
import java.util.Optional;
import java.util.UUID;

public interface CustomerPreferenceStore {

  Optional<CustomerNotificationPreferences> findByCustomerId(UUID customerId);

  void insert(CustomerNotificationPreferences prefs);

  void update(CustomerNotificationPreferences prefs);
}
