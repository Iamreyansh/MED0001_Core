package com.nammamedmate.notification.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.notification.application.port.out.CustomerPreferenceStore;
import com.nammamedmate.notification.application.port.out.PharmacyPreferenceStore;
import com.nammamedmate.notification.application.port.out.PreferenceAuditStore;
import com.nammamedmate.notification.application.port.out.RecipientIdentityPort;
import com.nammamedmate.notification.application.port.out.WhatsAppOptoutStore;
import com.nammamedmate.notification.domain.CustomerNotificationPreferences;
import com.nammamedmate.notification.domain.PharmacyNotificationPreferences;
import com.nammamedmate.notification.domain.PreferenceAuditEntry;
import com.nammamedmate.notification.domain.PreferenceChangeSource;
import com.nammamedmate.notification.domain.PreferenceEntityType;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PreferenceService {

  private static final Map<String, Boolean> CUSTOMER_MANDATORY =
      Map.of("order_updates", true, "account_critical", true);
  private static final Map<String, Boolean> PHARMACY_MANDATORY =
      Map.of(
          "order_alerts", true,
          "kyc_updates", true,
          "compliance_reminders", true);

  private final CustomerPreferenceStore customers;
  private final PharmacyPreferenceStore pharmacies;
  private final PreferenceAuditStore audits;
  private final WhatsAppOptoutStore optouts;
  private final RecipientIdentityPort identities;
  private final Clock clock;

  public PreferenceService(
      CustomerPreferenceStore customers,
      PharmacyPreferenceStore pharmacies,
      PreferenceAuditStore audits,
      WhatsAppOptoutStore optouts,
      RecipientIdentityPort identities,
      Clock clock) {
    this.customers = customers;
    this.pharmacies = pharmacies;
    this.audits = audits;
    this.optouts = optouts;
    this.identities = identities;
    this.clock = clock;
  }

  public Map<String, Object> getCustomerPreferences(UUID customerId) {
    CustomerNotificationPreferences prefs = ensureCustomer(customerId);
    boolean waOptout =
        identities.findPhoneByCustomerId(customerId).map(optouts::isActivelyOptedOut).orElse(false);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("customer_id", customerId.toString());
    data.put("channels", channelViews(prefs));
    data.put("categories", customerCategoryViews(prefs));
    data.put("whatsapp_optout_active", waOptout);
    data.put("updated_at", prefs.updatedAt().toString());
    return data;
  }

  public Map<String, Object> patchCustomerPreferences(
      UUID customerId, Map<String, Boolean> channels, Map<String, Boolean> categories) {
    assertNoMandatoryDisabled(categories, CUSTOMER_MANDATORY);
    CustomerNotificationPreferences old = ensureCustomer(customerId);
    Instant now = clock.instant();

    Boolean push = channelOr(channels, "push", old.pushEnabled());
    Boolean sms = channelOr(channels, "sms", old.smsEnabled());
    Boolean whatsapp = channelOr(channels, "whatsapp", old.whatsappEnabled());
    Boolean email = channelOr(channels, "email", old.emailEnabled());
    Boolean orderUpdates = categoryOr(categories, "order_updates", old.catOrderUpdates());
    Boolean accountCritical = categoryOr(categories, "account_critical", old.catAccountCritical());
    Boolean promotions = categoryOr(categories, "promotions", old.catPromotions());
    Boolean refill = categoryOr(categories, "refill_reminders", old.catRefillReminders());
    Boolean offers = categoryOr(categories, "offers", old.catOffers());

    CustomerNotificationPreferences updated =
        new CustomerNotificationPreferences(
            old.id(),
            customerId,
            push,
            sms,
            whatsapp,
            email,
            orderUpdates,
            accountCritical,
            promotions,
            refill,
            offers,
            old.createdAt(),
            now);
    customers.update(updated);
    writeAudit(
        PreferenceEntityType.CUSTOMER,
        customerId,
        customerId,
        PreferenceChangeSource.USER,
        old.snapshot(),
        updated.snapshot(),
        now);

    // BR-8: re-enabling WhatsApp clears WA-native STOP opt-out.
    if (!old.whatsappEnabled() && whatsapp) {
      identities.findPhoneByCustomerId(customerId).ifPresent(optouts::deactivateByPhone);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("updated", true);
    data.put("channels", updated.snapshot().get("channels"));
    data.put("categories", updated.snapshot().get("categories"));
    data.put("updated_at", now.toString());
    return data;
  }

  public Map<String, Object> getPharmacyPreferences(MedmatePrincipal principal) {
    UUID pharmacyId = requirePharmacyId(principal);
    PharmacyNotificationPreferences prefs = ensurePharmacy(pharmacyId);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacyId.toString());
    data.put("channels", pharmacyChannelViews(prefs));
    data.put("categories", pharmacyCategoryViews(prefs));
    data.put("updated_at", prefs.updatedAt().toString());
    return data;
  }

  public Map<String, Object> patchPharmacyPreferences(
      MedmatePrincipal principal, Map<String, Boolean> channels, Map<String, Boolean> categories) {
    if (principal.role() != AuthRole.PHARMACY_OWNER) {
      throw new AppException("FORBIDDEN", "Only pharmacy_owner may update preferences", 403);
    }
    assertNoMandatoryDisabled(categories, PHARMACY_MANDATORY);
    UUID pharmacyId = requirePharmacyId(principal);
    PharmacyNotificationPreferences old = ensurePharmacy(pharmacyId);
    Instant now = clock.instant();

    PharmacyNotificationPreferences updated =
        new PharmacyNotificationPreferences(
            old.id(),
            pharmacyId,
            channelOr(channels, "push", old.pushEnabled()),
            channelOr(channels, "sms", old.smsEnabled()),
            channelOr(channels, "whatsapp", old.whatsappEnabled()),
            channelOr(channels, "email", old.emailEnabled()),
            categoryOr(categories, "order_alerts", old.catOrderAlerts()),
            categoryOr(categories, "settlement_updates", old.catSettlementUpdates()),
            categoryOr(categories, "kyc_updates", old.catKycUpdates()),
            categoryOr(categories, "low_stock_alerts", old.catLowStockAlerts()),
            categoryOr(categories, "compliance_reminders", old.catComplianceReminders()),
            old.createdAt(),
            now);
    pharmacies.update(updated);
    writeAudit(
        PreferenceEntityType.PHARMACY,
        pharmacyId,
        principal.subject(),
        PreferenceChangeSource.USER,
        old.snapshot(),
        updated.snapshot(),
        now);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("updated", true);
    data.put("updated_at", now.toString());
    return data;
  }

  /** Used by unsubscribe link — disable promotions + audit. */
  public void disableCustomerEmailPromotions(
      UUID customerId, PreferenceChangeSource source, UUID changedBy) {
    if (customerId == null) {
      return;
    }
    CustomerNotificationPreferences old = ensureCustomer(customerId);
    if (!old.catPromotions() && !old.emailEnabled()) {
      return;
    }
    Instant now = clock.instant();
    CustomerNotificationPreferences updated =
        new CustomerNotificationPreferences(
            old.id(),
            customerId,
            old.pushEnabled(),
            old.smsEnabled(),
            old.whatsappEnabled(),
            old.emailEnabled(),
            old.catOrderUpdates(),
            old.catAccountCritical(),
            false,
            old.catRefillReminders(),
            old.catOffers(),
            old.createdAt(),
            now);
    customers.update(updated);
    writeAudit(
        PreferenceEntityType.CUSTOMER,
        customerId,
        changedBy,
        source,
        old.snapshot(),
        updated.snapshot(),
        now);
  }

  public CustomerNotificationPreferences ensureCustomer(UUID customerId) {
    return customers
        .findByCustomerId(customerId)
        .orElseGet(
            () -> {
              Instant now = clock.instant();
              CustomerNotificationPreferences prefs =
                  CustomerNotificationPreferences.defaults(Ids.newId(), customerId, now);
              customers.insert(prefs);
              return prefs;
            });
  }

  public PharmacyNotificationPreferences ensurePharmacy(UUID pharmacyId) {
    return pharmacies
        .findByPharmacyId(pharmacyId)
        .orElseGet(
            () -> {
              Instant now = clock.instant();
              PharmacyNotificationPreferences prefs =
                  PharmacyNotificationPreferences.defaults(Ids.newId(), pharmacyId, now);
              pharmacies.insert(prefs);
              return prefs;
            });
  }

  private void writeAudit(
      PreferenceEntityType type,
      UUID entityId,
      UUID changedBy,
      PreferenceChangeSource source,
      Map<String, Object> oldValues,
      Map<String, Object> newValues,
      Instant at) {
    audits.insert(
        new PreferenceAuditEntry(
            Ids.newId(), type, entityId, changedBy, source, oldValues, newValues, at));
  }

  private static UUID requirePharmacyId(MedmatePrincipal principal) {
    if (principal.pharmacyId() == null) {
      throw new AppException("FORBIDDEN", "Pharmacy context required", 403);
    }
    return principal.pharmacyId();
  }

  private static void assertNoMandatoryDisabled(
      Map<String, Boolean> categories, Map<String, Boolean> mandatory) {
    if (categories == null || categories.isEmpty()) {
      return;
    }
    for (Map.Entry<String, Boolean> e : categories.entrySet()) {
      if (e.getValue() != null && !e.getValue() && Boolean.TRUE.equals(mandatory.get(e.getKey()))) {
        throw new AppException(
            "CANNOT_DISABLE_MANDATORY_CATEGORY",
            "Category " + e.getKey() + " cannot be disabled",
            422);
      }
    }
  }

  private static boolean channelOr(Map<String, Boolean> channels, String key, boolean fallback) {
    if (channels == null || !channels.containsKey(key) || channels.get(key) == null) {
      return fallback;
    }
    return channels.get(key);
  }

  private static boolean categoryOr(Map<String, Boolean> categories, String key, boolean fallback) {
    if (categories == null || !categories.containsKey(key) || categories.get(key) == null) {
      return fallback;
    }
    return categories.get(key);
  }

  private static Map<String, Object> channelViews(CustomerNotificationPreferences prefs) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("push", enabledCanDisable(prefs.pushEnabled(), true));
    out.put("sms", enabledCanDisable(prefs.smsEnabled(), true));
    out.put("whatsapp", enabledCanDisable(prefs.whatsappEnabled(), true));
    out.put("email", enabledCanDisable(prefs.emailEnabled(), true));
    return out;
  }

  private static Map<String, Object> customerCategoryViews(CustomerNotificationPreferences prefs) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("order_updates", enabledCanDisable(prefs.catOrderUpdates(), false));
    out.put("account_critical", enabledCanDisable(prefs.catAccountCritical(), false));
    out.put("promotions", enabledCanDisable(prefs.catPromotions(), true));
    out.put("refill_reminders", enabledCanDisable(prefs.catRefillReminders(), true));
    out.put("offers", enabledCanDisable(prefs.catOffers(), true));
    return out;
  }

  private static Map<String, Object> pharmacyChannelViews(PharmacyNotificationPreferences prefs) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("push", enabledCanDisable(prefs.pushEnabled(), true));
    out.put("sms", enabledCanDisable(prefs.smsEnabled(), true));
    out.put("whatsapp", enabledCanDisable(prefs.whatsappEnabled(), true));
    out.put("email", enabledCanDisable(prefs.emailEnabled(), true));
    return out;
  }

  private static Map<String, Object> pharmacyCategoryViews(PharmacyNotificationPreferences prefs) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("order_alerts", enabledCanDisable(prefs.catOrderAlerts(), false));
    out.put("settlement_updates", enabledCanDisable(prefs.catSettlementUpdates(), true));
    out.put("kyc_updates", enabledCanDisable(prefs.catKycUpdates(), false));
    out.put("low_stock_alerts", enabledCanDisable(prefs.catLowStockAlerts(), true));
    out.put("compliance_reminders", enabledCanDisable(prefs.catComplianceReminders(), false));
    return out;
  }

  private static Map<String, Object> enabledCanDisable(boolean enabled, boolean canDisable) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("enabled", enabled);
    m.put("can_disable", canDisable);
    return m;
  }
}
