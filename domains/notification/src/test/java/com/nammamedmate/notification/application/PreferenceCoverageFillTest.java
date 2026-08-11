package com.nammamedmate.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.notification.adapter.out.client.StubAttachmentFetcher;
import com.nammamedmate.notification.adapter.out.client.StubSendGridClient;
import com.nammamedmate.notification.adapter.out.client.StubSesClient;
import com.nammamedmate.notification.application.port.out.PreferenceGatePort;
import com.nammamedmate.notification.domain.CustomerNotificationPreferences;
import com.nammamedmate.notification.domain.EmailCategory;
import com.nammamedmate.notification.domain.EmailTemplate;
import com.nammamedmate.notification.domain.NotificationUserType;
import com.nammamedmate.notification.domain.PharmacyNotificationPreferences;
import com.nammamedmate.notification.domain.PreferenceChangeSource;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PreferenceCoverageFillTest {

  private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");
  private static final UUID CUST = UUID.fromString("c2000001-0000-4000-8000-000000000001");
  private static final UUID CUST2 = UUID.fromString("c2000002-0000-4000-8000-000000000002");
  private static final UUID PHARM = UUID.fromString("a2000001-0000-4000-8000-0000000000aa");

  private Clock clock;
  private PreferenceTestFakes.FakeCustomerPreferenceStore customers;
  private PreferenceTestFakes.FakePharmacyPreferenceStore pharmacies;
  private PreferenceTestFakes.FakePreferenceAuditStore audits;
  private PreferenceTestFakes.FakeWhatsAppOptoutStore optouts;
  private PreferenceTestFakes.FakeRecipientIdentityPort identities;
  private PreferenceServiceAcTest.FakeEmailUnsubStore unsubscribes;
  private PreferenceService prefs;
  private PreferenceGateService gate;

  @BeforeEach
  void setUp() {
    clock = Clock.fixed(NOW, ZoneOffset.UTC);
    customers = new PreferenceTestFakes.FakeCustomerPreferenceStore();
    pharmacies = new PreferenceTestFakes.FakePharmacyPreferenceStore();
    audits = new PreferenceTestFakes.FakePreferenceAuditStore();
    optouts = new PreferenceTestFakes.FakeWhatsAppOptoutStore();
    identities = new PreferenceTestFakes.FakeRecipientIdentityPort();
    unsubscribes = new PreferenceServiceAcTest.FakeEmailUnsubStore();
    prefs = new PreferenceService(customers, pharmacies, audits, optouts, identities, clock);
    gate = new PreferenceGateService(customers, pharmacies, identities, unsubscribes);
  }

  @Test
  void coverageBranches() {
    prefs.disableCustomerEmailPromotions(null, PreferenceChangeSource.SYSTEM, null);

    customers.insert(
        new CustomerNotificationPreferences(
            Ids.newId(), CUST, true, true, true, false, true, true, false, true, true, NOW, NOW));
    prefs.disableCustomerEmailPromotions(CUST, PreferenceChangeSource.SPAM_REPORT, CUST);

    prefs.patchCustomerPreferences(CUST, Map.of("whatsapp", false), null);
    prefs.patchCustomerPreferences(CUST, Map.of("whatsapp", true), Map.of());
    prefs.patchCustomerPreferences(CUST, null, null);
    Map<String, Boolean> channelsWithNull = new HashMap<>();
    channelsWithNull.put("push", null);
    Map<String, Boolean> catsWithNull = new HashMap<>();
    catsWithNull.put("offers", null);
    prefs.patchCustomerPreferences(CUST, channelsWithNull, catsWithNull);

    assertThatThrownBy(
            () -> prefs.patchCustomerPreferences(CUST, Map.of(), Map.of("account_critical", false)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CANNOT_DISABLE_MANDATORY_CATEGORY");

    MedmatePrincipal noPharmacy =
        new MedmatePrincipal(CUST, AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> prefs.getPharmacyPreferences(noPharmacy))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    MedmatePrincipal owner =
        new MedmatePrincipal(CUST, AuthRole.PHARMACY_OWNER, PHARM, TokenScope.FULL, "j");
    prefs.getPharmacyPreferences(owner);
    assertThatThrownBy(
            () ->
                prefs.patchPharmacyPreferences(
                    owner, Map.of(), Map.of("compliance_reminders", false)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CANNOT_DISABLE_MANDATORY_CATEGORY");
    prefs.patchPharmacyPreferences(owner, null, null);
    Map<String, Boolean> mandatoryNull = new HashMap<>();
    mandatoryNull.put("order_alerts", null);
    prefs.patchPharmacyPreferences(owner, Map.of(), mandatoryNull);
    prefs.patchPharmacyPreferences(owner, Map.of("sms", true), Map.of("order_alerts", true));
    // already-disabled whatsapp → whatsapp false again (BR-8 compound false)
    prefs.patchCustomerPreferences(CUST, Map.of("whatsapp", false), Map.of());
    prefs.patchCustomerPreferences(CUST, Map.of("whatsapp", false), Map.of());
    // promotions false, email true → disable still updates
    customers.update(
        new CustomerNotificationPreferences(
            Ids.newId(), CUST, true, true, true, true, true, true, false, true, true, NOW, NOW));
    prefs.disableCustomerEmailPromotions(CUST, PreferenceChangeSource.SYSTEM, CUST);

    assertThat(gate.allowsPush(CUST, NotificationUserType.CUSTOMER, "TRANSACTIONAL")).isTrue();
    assertThat(gate.allowsPush(CUST, NotificationUserType.PHARMACY_STAFF, "TRANSACTIONAL"))
        .isTrue();
    assertThat(gate.allowsPush(CUST, NotificationUserType.RIDER, null)).isTrue();
    assertThat(gate.allowsEmail(CUST, "x@y.com", "MARKETING")).isFalse();
    assertThat(gate.allowsEmail(null, null, null)).isTrue();
    assertThat(gate.allowsEmail(null, "  ", "LIFECYCLE")).isTrue();
    assertThat(gate.allowsEmail(Ids.newId(), "z@z.com", "weird")).isTrue();
    assertThat(gate.allowsEmail(Ids.newId(), "z@z.com", "order_updates")).isTrue();
    assertThat(gate.allowsEmail(Ids.newId(), "z@z.com", "account_critical")).isTrue();
    assertThat(gate.allowsEmail(Ids.newId(), "z@z.com", "transactional")).isTrue();
    assertThat(gate.allowsEmail(Ids.newId(), "z@z.com", "   ")).isTrue();
    // email channel on + promotions off
    customers.insert(
        new CustomerNotificationPreferences(
            Ids.newId(),
            UUID.fromString("c2000003-0000-4000-8000-000000000003"),
            true,
            true,
            true,
            true,
            true,
            true,
            false,
            true,
            true,
            NOW,
            NOW));
    assertThat(
            gate.allowsEmail(
                UUID.fromString("c2000003-0000-4000-8000-000000000003"), "m@n.com", "MARKETING"))
        .isFalse();
    assertThat(
            gate.allowsEmail(
                UUID.fromString("c2000003-0000-4000-8000-000000000003"),
                "m@n.com",
                "TRANSACTIONAL"))
        .isTrue();
    assertThat(gate.allowsPharmacyChannel(null, "sms", "order_alerts")).isTrue();
    assertThat(gate.allowsPharmacyChannel(PHARM, "sms", "order_alerts")).isTrue();
    // pharmacy channel on + category off
    UUID ph2 = UUID.fromString("a2000003-0000-4000-8000-0000000000aa");
    pharmacies.insert(
        new PharmacyNotificationPreferences(
            Ids.newId(), ph2, true, true, true, true, true, false, true, true, true, NOW, NOW));
    assertThat(gate.allowsPharmacyChannel(ph2, "sms", "settlement_updates")).isFalse();
    assertThat(gate.allowsPharmacyChannel(ph2, "sms", "order_alerts")).isTrue();

    customers.insert(
        new CustomerNotificationPreferences(
            Ids.newId(),
            CUST2,
            false,
            false,
            false,
            true,
            false,
            true,
            false,
            false,
            false,
            NOW,
            NOW));
    identities.link(CUST2, "+911111111111");
    assertThat(gate.allowsPush(CUST2, NotificationUserType.CUSTOMER, "TRANSACTIONAL")).isFalse();
    assertThat(gate.allowsPush(CUST2, NotificationUserType.CUSTOMER, "account_critical")).isFalse();
    // channel on, category off
    customers.update(
        new CustomerNotificationPreferences(
            Ids.newId(),
            CUST2,
            true,
            true,
            true,
            true,
            false,
            true,
            false,
            false,
            false,
            NOW,
            NOW));
    assertThat(gate.allowsPush(CUST2, NotificationUserType.CUSTOMER, "TRANSACTIONAL")).isFalse();
    assertThat(gate.allowsPush(CUST2, NotificationUserType.CUSTOMER, "account_critical")).isTrue();
    assertThat(gate.allowsSms("+911111111111", "PROMOTIONAL")).isFalse();
    assertThat(gate.allowsSms("+911111111111", "OTP")).isTrue();
    assertThat(gate.allowsWhatsApp("+911111111111")).isTrue();
    // sms/email channel off short-circuit on &&
    customers.update(
        new CustomerNotificationPreferences(
            Ids.newId(), CUST2, true, false, true, false, true, true, true, true, true, NOW, NOW));
    assertThat(gate.allowsSms("+911111111111", "OTP")).isFalse();
    assertThat(gate.allowsEmail(CUST2, "e@e.com", "TRANSACTIONAL")).isFalse();
    assertThat(gate.allowsEmail(CUST2, "e@e.com", "MARKETING")).isFalse();

    CustomerNotificationPreferences gated =
        new CustomerNotificationPreferences(
            Ids.newId(), CUST2, true, true, true, true, false, true, false, false, false, NOW, NOW);
    assertThat(gated.channelEnabled("PUSH")).isTrue();
    assertThat(gated.channelEnabled("SMS")).isTrue();
    assertThat(gated.channelEnabled("WHATSAPP")).isTrue();
    assertThat(gated.channelEnabled("EMAIL")).isTrue();
    assertThat(gated.categoryEnabled("order_updates")).isFalse();
    assertThat(gated.categoryEnabled("account_critical")).isTrue();
    assertThat(gated.categoryEnabled("promotions")).isFalse();
    assertThat(gated.categoryEnabled("promotional")).isFalse();
    assertThat(gated.categoryEnabled("marketing")).isFalse();
    assertThat(gated.categoryEnabled("refill_reminders")).isFalse();
    assertThat(gated.categoryEnabled("lifecycle")).isFalse();
    assertThat(gated.categoryEnabled("offers")).isFalse();
    assertThat(gated.categoryEnabled("")).isTrue();
    assertThat(gated.categoryEnabled(null)).isTrue();
    assertThat(gated.channelEnabled(null)).isTrue();
    assertThat(gated.channelEnabled("other")).isTrue();
    // all channels off for switch-arm coverage
    CustomerNotificationPreferences off =
        new CustomerNotificationPreferences(
            Ids.newId(), CUST2, false, false, false, false, true, true, true, true, true, NOW, NOW);
    assertThat(off.channelEnabled("push")).isFalse();
    assertThat(off.channelEnabled("sms")).isFalse();
    assertThat(off.channelEnabled("whatsapp")).isFalse();
    assertThat(off.channelEnabled("email")).isFalse();

    PharmacyNotificationPreferences ph =
        PharmacyNotificationPreferences.defaults(Ids.newId(), PHARM, NOW);
    pharmacies.update(
        new PharmacyNotificationPreferences(
            ph.id(), PHARM, false, false, false, false, false, false, false, false, false, NOW,
            NOW));
    // ensure row exists with known id
    pharmacies.insert(
        new PharmacyNotificationPreferences(
            Ids.newId(),
            UUID.fromString("a2000002-0000-4000-8000-0000000000aa"),
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            NOW,
            NOW));
    PharmacyNotificationPreferences loaded =
        pharmacies
            .findByPharmacyId(UUID.fromString("a2000002-0000-4000-8000-0000000000aa"))
            .orElseThrow();
    assertThat(loaded.channelEnabled(null)).isTrue();
    assertThat(loaded.channelEnabled("push")).isFalse();
    assertThat(loaded.channelEnabled("sms")).isFalse();
    assertThat(loaded.channelEnabled("whatsapp")).isFalse();
    assertThat(loaded.channelEnabled("email")).isFalse();
    assertThat(loaded.channelEnabled("other")).isTrue();
    assertThat(loaded.categoryEnabled(null)).isTrue();
    assertThat(loaded.categoryEnabled("order_alerts")).isFalse();
    assertThat(loaded.categoryEnabled("transactional")).isFalse();
    assertThat(loaded.categoryEnabled("order_updates")).isFalse();
    assertThat(loaded.categoryEnabled("settlement_updates")).isFalse();
    assertThat(loaded.categoryEnabled("kyc_updates")).isFalse();
    assertThat(loaded.categoryEnabled("account_critical")).isFalse();
    assertThat(loaded.categoryEnabled("low_stock_alerts")).isFalse();
    assertThat(loaded.categoryEnabled("promotions")).isFalse();
    assertThat(loaded.categoryEnabled("promotional")).isFalse();
    assertThat(loaded.categoryEnabled("compliance_reminders")).isFalse();
    assertThat(loaded.categoryEnabled("unknown")).isTrue();
    assertThat(
            gate.allowsPharmacyChannel(
                UUID.fromString("a2000002-0000-4000-8000-0000000000aa"), "push", "order_alerts"))
        .isFalse();
  }

  @Test
  void emailSendPreferenceBlocked() {
    EmailServiceAcTest.FakeEmailTemplateStore templates =
        new EmailServiceAcTest.FakeEmailTemplateStore();
    templates.upsert(
        new EmailTemplate(
            "WEEKLY_OFFERS",
            "Offers",
            "hi",
            "<p>h</p>",
            "t",
            EmailCategory.MARKETING,
            true,
            1,
            null,
            NOW,
            NOW));
    PreferenceGatePort deny =
        new PreferenceGatePort() {
          @Override
          public boolean allowsPush(UUID userId, NotificationUserType userType, String category) {
            return true;
          }

          @Override
          public boolean allowsSms(String toPhone, String category) {
            return true;
          }

          @Override
          public boolean allowsWhatsApp(String toPhone) {
            return true;
          }

          @Override
          public boolean allowsEmail(UUID customerId, String toEmail, String category) {
            return false;
          }
        };
    EmailSendService send =
        new EmailSendService(
            templates,
            new EmailServiceAcTest.FakeEmailDeliveryLogStore(),
            new EmailServiceAcTest.FakeEmailBounceStore(),
            unsubscribes,
            new StubSendGridClient(),
            new StubSesClient(),
            new StubAttachmentFetcher(),
            channel -> Optional.of("SENDGRID"),
            deny,
            new UnsubscribeTokenService("test-email-unsubscribe-secret-key!!", clock),
            clock,
            "http://localhost:8080");
    assertThatThrownBy(
            () ->
                send.send(
                    new EmailSendService.SendCommand(
                        "a@b.com", null, "WEEKLY_OFFERS", Map.of(), List.of(), CUST)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PREFERENCE_BLOCKED");
  }
}
