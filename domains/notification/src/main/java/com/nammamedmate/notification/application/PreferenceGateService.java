package com.nammamedmate.notification.application;

import com.nammamedmate.notification.application.port.out.CustomerPreferenceStore;
import com.nammamedmate.notification.application.port.out.PharmacyPreferenceStore;
import com.nammamedmate.notification.application.port.out.PreferenceGatePort;
import com.nammamedmate.notification.application.port.out.RecipientIdentityPort;
import com.nammamedmate.notification.domain.CustomerNotificationPreferences;
import com.nammamedmate.notification.domain.NotificationUserType;
import com.nammamedmate.notification.domain.PharmacyNotificationPreferences;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PreferenceGateService implements PreferenceGatePort {

  private final CustomerPreferenceStore customers;
  private final PharmacyPreferenceStore pharmacies;
  private final RecipientIdentityPort identities;

  public PreferenceGateService(
      CustomerPreferenceStore customers,
      PharmacyPreferenceStore pharmacies,
      RecipientIdentityPort identities) {
    this.customers = customers;
    this.pharmacies = pharmacies;
    this.identities = identities;
  }

  @Override
  public boolean allowsPush(UUID userId, NotificationUserType userType, String category) {
    if (userId == null) {
      return true;
    }
    if (userType == NotificationUserType.CUSTOMER) {
      return customers
          .findByCustomerId(userId)
          .map(p -> p.channelEnabled("push") && p.categoryEnabled(category))
          .orElse(true);
    }
    return true;
  }

  @Override
  public boolean allowsSms(String toPhone, String category) {
    Optional<CustomerNotificationPreferences> prefs = customerPrefsByPhone(toPhone);
    if (prefs.isEmpty()) {
      return true;
    }
    CustomerNotificationPreferences p = prefs.get();
    return p.channelEnabled("sms") && p.categoryEnabled(category);
  }

  @Override
  public boolean allowsWhatsApp(String toPhone) {
    return false; // WhatsApp vendor removed
  }

  @Override
  public boolean allowsEmail(UUID customerId, String toEmail, String category) {
    return false; // Email vendor removed
  }

  public boolean allowsPharmacyChannel(UUID pharmacyId, String channel, String category) {
    if (pharmacyId == null) {
      return true;
    }
    if ("whatsapp".equalsIgnoreCase(channel) || "email".equalsIgnoreCase(channel)) {
      return false;
    }
    Optional<PharmacyNotificationPreferences> prefs = pharmacies.findByPharmacyId(pharmacyId);
    if (prefs.isEmpty()) {
      return true;
    }
    PharmacyNotificationPreferences p = prefs.get();
    return p.channelEnabled(channel) && p.categoryEnabled(category);
  }

  private Optional<CustomerNotificationPreferences> customerPrefsByPhone(String phone) {
    return identities.findCustomerIdByPhone(phone).flatMap(customers::findByCustomerId);
  }
}
