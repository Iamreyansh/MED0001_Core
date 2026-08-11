package com.nammamedmate.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.notification.application.port.out.EmailUnsubscribeStore;
import com.nammamedmate.notification.domain.CustomerNotificationPreferences;
import com.nammamedmate.notification.domain.EmailUnsubscribe;
import com.nammamedmate.notification.domain.EmailUnsubscribeSource;
import com.nammamedmate.notification.domain.NotificationUserType;
import com.nammamedmate.notification.domain.PreferenceChangeSource;
import com.nammamedmate.notification.domain.PreferenceEntityType;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PreferenceServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-08-11T08:00:00Z");
  private static final UUID CUST = UUID.fromString("c0000001-0000-4000-8000-000000000001");
  private static final UUID PHARM = UUID.fromString("a0000001-0000-4000-8000-0000000000aa");
  private static final UUID OWNER = UUID.fromString("b0000001-0000-4000-8000-0000000000bb");
  private static final UUID STAFF = UUID.fromString("d0000001-0000-4000-8000-0000000000dd");
  private static final String PHONE = "+919876543210";
  private static final String EMAIL = "ravi.kumar@example.com";

  private Clock clock;
  private PreferenceTestFakes.FakeCustomerPreferenceStore customers;
  private PreferenceTestFakes.FakePharmacyPreferenceStore pharmacies;
  private PreferenceTestFakes.FakePreferenceAuditStore audits;
  private PreferenceTestFakes.FakeWhatsAppOptoutStore optouts;
  private PreferenceTestFakes.FakeRecipientIdentityPort identities;
  private FakeEmailUnsubStore unsubscribes;
  private PreferenceService prefs;
  private PreferenceGateService gate;
  private UnsubscribeTokenService tokens;
  private EmailUnsubscribeService unsubscribe;

  @BeforeEach
  void setUp() {
    clock = Clock.fixed(NOW, ZoneOffset.UTC);
    customers = new PreferenceTestFakes.FakeCustomerPreferenceStore();
    pharmacies = new PreferenceTestFakes.FakePharmacyPreferenceStore();
    audits = new PreferenceTestFakes.FakePreferenceAuditStore();
    optouts = new PreferenceTestFakes.FakeWhatsAppOptoutStore();
    identities = new PreferenceTestFakes.FakeRecipientIdentityPort();
    identities.link(CUST, PHONE);
    unsubscribes = new FakeEmailUnsubStore();
    prefs = new PreferenceService(customers, pharmacies, audits, optouts, identities, clock);
    gate = new PreferenceGateService(customers, pharmacies, identities, unsubscribes);
    tokens = new UnsubscribeTokenService("test-email-unsubscribe-secret-key!!", clock);
    unsubscribe = new EmailUnsubscribeService(unsubscribes, tokens, prefs, clock);
  }

  @Test
  void ac001_cannotDisableMandatoryOrderUpdates() {
    assertThatThrownBy(
            () -> prefs.patchCustomerPreferences(CUST, Map.of(), Map.of("order_updates", false)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CANNOT_DISABLE_MANDATORY_CATEGORY");
  }

  @Test
  void ac002_getMarksMandatoryCanDisableFalse() {
    @SuppressWarnings("unchecked")
    Map<String, Object> data = prefs.getCustomerPreferences(CUST);
    Map<String, Object> categories = (Map<String, Object>) data.get("categories");
    @SuppressWarnings("unchecked")
    Map<String, Object> order = (Map<String, Object>) categories.get("order_updates");
    @SuppressWarnings("unchecked")
    Map<String, Object> critical = (Map<String, Object>) categories.get("account_critical");
    assertThat(order.get("can_disable")).isEqualTo(false);
    assertThat(critical.get("can_disable")).isEqualTo(false);
    assertThat(order.get("enabled")).isEqualTo(true);
  }

  @Test
  void ac003_unsubscribeDisablesPromotionsAndAddsEmailUnsub() {
    String token = tokens.issue(EMAIL, CUST);
    Map<String, Object> result = unsubscribe.unsubscribe(token);
    assertThat(result.get("unsubscribed")).isEqualTo(true);
    assertThat(unsubscribes.isActivelyUnsubscribed(EMAIL)).isTrue();
    CustomerNotificationPreferences row = customers.findByCustomerId(CUST).orElseThrow();
    assertThat(row.catPromotions()).isFalse();
    assertThat(audits.entries).isNotEmpty();
    assertThat(audits.entries.get(0).changeSource())
        .isEqualTo(PreferenceChangeSource.UNSUBSCRIBE_LINK);
  }

  @Test
  void ac004_expiredTokenReturns410() {
    Clock past = Clock.fixed(NOW.minusSeconds(8L * 24 * 60 * 60), ZoneOffset.UTC);
    UnsubscribeTokenService expiredTokens =
        new UnsubscribeTokenService("test-email-unsubscribe-secret-key!!", past);
    String token = expiredTokens.issue(EMAIL, CUST);
    assertThatThrownBy(() -> unsubscribe.unsubscribe(token))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("TOKEN_EXPIRED");
    assertThatThrownBy(() -> unsubscribe.unsubscribe(token))
        .extracting(ex -> ((AppException) ex).httpStatus())
        .isEqualTo(410);
  }

  @Test
  void ac005_pharmacyStaffPatchForbidden() {
    MedmatePrincipal staff =
        new MedmatePrincipal(STAFF, AuthRole.PHARMACY_STAFF, PHARM, TokenScope.FULL, "j");
    assertThatThrownBy(() -> prefs.patchPharmacyPreferences(staff, Map.of("sms", false), Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void ac006_whatsappDisabledSkipsWaOnly() {
    prefs.patchCustomerPreferences(CUST, Map.of("whatsapp", false), Map.of());
    assertThat(gate.allowsWhatsApp(PHONE)).isFalse();
    assertThat(gate.allowsSms(PHONE, "PROMOTIONAL")).isTrue();
    assertThat(gate.allowsPush(CUST, NotificationUserType.CUSTOMER, "TRANSACTIONAL")).isTrue();
    assertThat(gate.allowsEmail(CUST, EMAIL, "TRANSACTIONAL")).isTrue();
  }

  @Test
  void ac007_waStopShowsOptoutActiveEvenIfChannelEnabled() {
    prefs.getCustomerPreferences(CUST);
    optouts.upsertActive(
        Ids.newId(),
        PHONE,
        com.nammamedmate.notification.domain.WhatsAppOptoutSource.WA_REPLY,
        NOW);
    Map<String, Object> data = prefs.getCustomerPreferences(CUST);
    assertThat(data.get("whatsapp_optout_active")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> channels = (Map<String, Object>) data.get("channels");
    @SuppressWarnings("unchecked")
    Map<String, Object> wa = (Map<String, Object>) channels.get("whatsapp");
    assertThat(wa.get("enabled")).isEqualTo(true);
  }

  @Test
  void ac008_everyChangeCreatesAudit() {
    prefs.patchCustomerPreferences(CUST, Map.of("email", false), Map.of("offers", false));
    assertThat(audits.entries).hasSize(1);
    assertThat(audits.entries.get(0).entityType()).isEqualTo(PreferenceEntityType.CUSTOMER);
    assertThat(audits.entries.get(0).changeSource()).isEqualTo(PreferenceChangeSource.USER);

    MedmatePrincipal owner =
        new MedmatePrincipal(OWNER, AuthRole.PHARMACY_OWNER, PHARM, TokenScope.FULL, "j");
    prefs.patchPharmacyPreferences(owner, Map.of("sms", false), Map.of("low_stock_alerts", false));
    assertThat(audits.entries).hasSize(2);
    assertThat(audits.entries.get(1).entityType()).isEqualTo(PreferenceEntityType.PHARMACY);
  }

  @Test
  void reEnableWhatsAppClearsOptout() {
    prefs.patchCustomerPreferences(CUST, Map.of("whatsapp", false), Map.of());
    optouts.upsertActive(
        Ids.newId(),
        PHONE,
        com.nammamedmate.notification.domain.WhatsAppOptoutSource.WA_REPLY,
        NOW);
    prefs.patchCustomerPreferences(CUST, Map.of("whatsapp", true), Map.of());
    assertThat(optouts.isActivelyOptedOut(PHONE)).isFalse();
  }

  @Test
  void pharmacyGetAndMandatory() {
    MedmatePrincipal owner =
        new MedmatePrincipal(OWNER, AuthRole.PHARMACY_OWNER, PHARM, TokenScope.FULL, "j");
    Map<String, Object> data = prefs.getPharmacyPreferences(owner);
    assertThat(data.get("pharmacy_id")).isEqualTo(PHARM.toString());
    assertThatThrownBy(
            () -> prefs.patchPharmacyPreferences(owner, Map.of(), Map.of("order_alerts", false)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CANNOT_DISABLE_MANDATORY_CATEGORY");
  }

  @Test
  void gateBlocksUnsubscribedEmailAndUnknownPhoneAllows() {
    unsubscribes.upsertActive(Ids.newId(), EMAIL, EmailUnsubscribeSource.LINK_CLICK, NOW);
    assertThat(gate.allowsEmail(null, EMAIL, "MARKETING")).isFalse();
    assertThat(gate.allowsEmail(null, EMAIL, "TRANSACTIONAL")).isTrue();
    assertThat(gate.allowsSms("+910000000000", "PROMOTIONAL")).isTrue();
    assertThat(gate.allowsWhatsApp("+910000000000")).isTrue();
    assertThat(gate.allowsPush(null, NotificationUserType.CUSTOMER, "TRANSACTIONAL")).isTrue();
    assertThat(gate.allowsPharmacyChannel(PHARM, "push", "order_alerts")).isTrue();
    prefs.ensurePharmacy(PHARM);
    prefs.patchPharmacyPreferences(
        new MedmatePrincipal(OWNER, AuthRole.PHARMACY_OWNER, PHARM, TokenScope.FULL, "j"),
        Map.of("push", false),
        Map.of());
    assertThat(gate.allowsPharmacyChannel(PHARM, "push", "order_alerts")).isFalse();
  }

  static final class FakeEmailUnsubStore implements EmailUnsubscribeStore {
    private final ConcurrentHashMap<String, EmailUnsubscribe> active = new ConcurrentHashMap<>();

    @Override
    public void upsertActive(UUID id, String email, EmailUnsubscribeSource source, Instant at) {
      active.put(email, new EmailUnsubscribe(id, email, source, at, true));
    }

    @Override
    public boolean isActivelyUnsubscribed(String email) {
      return active.containsKey(email);
    }

    @Override
    public Optional<EmailUnsubscribe> findActive(String email) {
      return Optional.ofNullable(active.get(email));
    }
  }
}
