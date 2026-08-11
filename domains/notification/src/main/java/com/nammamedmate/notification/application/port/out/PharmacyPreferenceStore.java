package com.nammamedmate.notification.application.port.out;

import com.nammamedmate.notification.domain.PharmacyNotificationPreferences;
import java.util.Optional;
import java.util.UUID;

public interface PharmacyPreferenceStore {

  Optional<PharmacyNotificationPreferences> findByPharmacyId(UUID pharmacyId);

  void insert(PharmacyNotificationPreferences prefs);

  void update(PharmacyNotificationPreferences prefs);
}
