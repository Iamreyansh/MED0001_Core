package com.nammamedmate.notification.application;

import com.nammamedmate.notification.application.port.out.CustomerPreferenceStore;
import com.nammamedmate.notification.application.port.out.PharmacyPreferenceStore;
import com.nammamedmate.notification.application.port.out.PreferenceAuditStore;
import com.nammamedmate.notification.application.port.out.RecipientIdentityPort;
import com.nammamedmate.notification.application.port.out.WhatsAppOptoutStore;
import com.nammamedmate.notification.domain.CustomerNotificationPreferences;
import com.nammamedmate.notification.domain.PharmacyNotificationPreferences;
import com.nammamedmate.notification.domain.PreferenceAuditEntry;
import com.nammamedmate.notification.domain.WhatsAppOptout;
import com.nammamedmate.notification.domain.WhatsAppOptoutSource;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Shared in-memory fakes for preference tests. */
final class PreferenceTestFakes {

  private PreferenceTestFakes() {}

  static PreferenceService preferenceService(Clock clock) {
    return new PreferenceService(
        new FakeCustomerPreferenceStore(),
        new FakePharmacyPreferenceStore(),
        new FakePreferenceAuditStore(),
        new FakeWhatsAppOptoutStore(),
        new FakeRecipientIdentityPort(),
        clock);
  }

  static final class FakeCustomerPreferenceStore implements CustomerPreferenceStore {
    final ConcurrentHashMap<UUID, CustomerNotificationPreferences> byCustomer =
        new ConcurrentHashMap<>();

    @Override
    public Optional<CustomerNotificationPreferences> findByCustomerId(UUID customerId) {
      return Optional.ofNullable(byCustomer.get(customerId));
    }

    @Override
    public void insert(CustomerNotificationPreferences prefs) {
      byCustomer.put(prefs.customerId(), prefs);
    }

    @Override
    public void update(CustomerNotificationPreferences prefs) {
      byCustomer.put(prefs.customerId(), prefs);
    }
  }

  static final class FakePharmacyPreferenceStore implements PharmacyPreferenceStore {
    final ConcurrentHashMap<UUID, PharmacyNotificationPreferences> byPharmacy =
        new ConcurrentHashMap<>();

    @Override
    public Optional<PharmacyNotificationPreferences> findByPharmacyId(UUID pharmacyId) {
      return Optional.ofNullable(byPharmacy.get(pharmacyId));
    }

    @Override
    public void insert(PharmacyNotificationPreferences prefs) {
      byPharmacy.put(prefs.pharmacyId(), prefs);
    }

    @Override
    public void update(PharmacyNotificationPreferences prefs) {
      byPharmacy.put(prefs.pharmacyId(), prefs);
    }
  }

  static final class FakePreferenceAuditStore implements PreferenceAuditStore {
    final List<PreferenceAuditEntry> entries = new ArrayList<>();

    @Override
    public void insert(PreferenceAuditEntry entry) {
      entries.add(entry);
    }
  }

  static final class FakeWhatsAppOptoutStore implements WhatsAppOptoutStore {
    final ConcurrentHashMap<String, WhatsAppOptout> active = new ConcurrentHashMap<>();

    @Override
    public boolean isActivelyOptedOut(String phone) {
      return active.containsKey(phone);
    }

    @Override
    public void upsertActive(UUID id, String phone, WhatsAppOptoutSource source, Instant at) {
      active.put(phone, new WhatsAppOptout(id, phone, source, at, true));
    }

    @Override
    public void deactivateByPhone(String phone) {
      active.remove(phone);
    }

    @Override
    public Optional<WhatsAppOptout> findActiveByPhone(String phone) {
      return Optional.ofNullable(active.get(phone));
    }
  }

  static final class FakeRecipientIdentityPort implements RecipientIdentityPort {
    final ConcurrentHashMap<String, UUID> phoneToCustomer = new ConcurrentHashMap<>();
    final ConcurrentHashMap<UUID, String> customerToPhone = new ConcurrentHashMap<>();

    void link(UUID customerId, String phone) {
      phoneToCustomer.put(phone, customerId);
      customerToPhone.put(customerId, phone);
    }

    @Override
    public Optional<UUID> findCustomerIdByPhone(String phone) {
      return Optional.ofNullable(phoneToCustomer.get(phone));
    }

    @Override
    public Optional<String> findPhoneByCustomerId(UUID customerId) {
      return Optional.ofNullable(customerToPhone.get(customerId));
    }
  }
}
