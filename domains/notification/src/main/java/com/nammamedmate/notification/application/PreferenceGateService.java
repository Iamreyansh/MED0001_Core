package com.nammamedmate.notification.application;

import com.nammamedmate.notification.application.port.out.CustomerPreferenceStore;
import com.nammamedmate.notification.application.port.out.EmailUnsubscribeStore;
import com.nammamedmate.notification.application.port.out.PharmacyPreferenceStore;
import com.nammamedmate.notification.application.port.out.PreferenceGatePort;
import com.nammamedmate.notification.application.port.out.RecipientIdentityPort;
import com.nammamedmate.notification.domain.CustomerNotificationPreferences;
import com.nammamedmate.notification.domain.EmailCategory;
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
  private final EmailUnsubscribeStore unsubscribes;

  public PreferenceGateService(
      CustomerPreferenceStore customers,
      PharmacyPreferenceStore pharmacies,
      RecipientIdentityPort identities,
      EmailUnsubscribeStore unsubscribes) {
    this.customers = customers;
    this.pharmacies = pharmacies;
    this.identities = identities;
    this.unsubscribes = unsubscribes;
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
    // Pharmacy staff / rider: no pharmacy_id on push recipient — allow unless we later scope.
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
    Optional<CustomerNotificationPreferences> prefs = customerPrefsByPhone(toPhone);
    if (prefs.isEmpty()) {
      return true;
    }
    return prefs.get().channelEnabled("whatsapp");
  }

  @Override
  public boolean allowsEmail(UUID customerId, String toEmail, String category) {
    String email = toEmail == null ? "" : toEmail.trim().toLowerCase();
    boolean transactional = isTransactionalEmailCategory(category);
    if (!transactional && !email.isEmpty() && unsubscribes.isActivelyUnsubscribed(email)) {
      return false;
    }
    if (customerId == null) {
      return true;
    }
    Optional<CustomerNotificationPreferences> prefs = customers.findByCustomerId(customerId);
    if (prefs.isEmpty()) {
      return true;
    }
    CustomerNotificationPreferences p = prefs.get();
    return p.channelEnabled("email") && p.categoryEnabled(category);
  }

  /** Optional pharmacy-scoped check when pharmacy_id is known. */
  public boolean allowsPharmacyChannel(UUID pharmacyId, String channel, String category) {
    if (pharmacyId == null) {
      return true;
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

  private static boolean isTransactionalEmailCategory(String category) {
    if (category == null || category.isBlank()) {
      return true;
    }
    String c = category.trim().toLowerCase();
    if ("transactional".equals(c) || "order_updates".equals(c) || "account_critical".equals(c)) {
      return true;
    }
    try {
      return EmailCategory.parse(category).isTransactional();
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
