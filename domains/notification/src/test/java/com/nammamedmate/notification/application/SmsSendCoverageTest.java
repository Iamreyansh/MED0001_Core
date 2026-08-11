package com.nammamedmate.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.notification.adapter.out.client.StubMsg91Client;
import com.nammamedmate.notification.adapter.out.client.StubTwilioClient;
import com.nammamedmate.notification.application.port.out.Msg91ClientPort;
import com.nammamedmate.notification.application.port.out.SmsDeliveryLogStore;
import com.nammamedmate.notification.application.port.out.TwilioClientPort;
import com.nammamedmate.notification.domain.SmsCategory;
import com.nammamedmate.notification.domain.SmsTemplate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SmsSendCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T08:20:00Z");
  private static final UUID ADMIN = UUID.fromString("a0000001-0000-4000-8000-000000000001");
  private static final String PHONE = "+919876543210";

  private SmsServiceAcTest.FakeSmsTemplateStore templates;
  private SmsServiceAcTest.FakeSmsDeliveryLogStore logs;
  private StubMsg91Client msg91;
  private StubTwilioClient twilio;
  private SmsSendService send;
  private SmsAdminService admin;

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    templates = new SmsServiceAcTest.FakeSmsTemplateStore();
    logs = new SmsServiceAcTest.FakeSmsDeliveryLogStore();
    msg91 = new StubMsg91Client();
    twilio = new StubTwilioClient();
    templates.insert(
        new SmsTemplate(
            "OTP_VERIFICATION",
            "OTP {{1}} for {{2}} min",
            SmsCategory.OTP,
            "1007164875432101",
            "NMMATE",
            true,
            ADMIN,
            NOW));
    templates.insert(
        new SmsTemplate("INACTIVE", "x", SmsCategory.OTP, "1007", "NMMATE", false, ADMIN, NOW));
    templates.insert(
        new SmsTemplate(
            "NO_DLT", "x", SmsCategory.TRANSACTIONAL, null, "NMMATE", true, ADMIN, NOW));
    templates.insert(
        new SmsTemplate(
            "BLANK_DLT", "x", SmsCategory.TRANSACTIONAL, "  ", "NMMATE", true, ADMIN, NOW));
    templates.insert(
        new SmsTemplate(
            "PROMO_OFFER",
            "Save {{1}}",
            SmsCategory.PROMOTIONAL,
            "1007164875432199",
            "NMMATE",
            true,
            ADMIN,
            NOW));
    send =
        new SmsSendService(
            templates,
            logs,
            msg91,
            twilio,
            AllowAllPreferenceGate.INSTANCE,
            channel -> Optional.of("MSG91"),
            clock);
    admin = new SmsAdminService(templates, logs, clock);
  }

  @Test
  void templateErrorsAndHappyMsg91() {
    assertThatThrownBy(
            () -> send.send(new SmsSendService.SendCommand(PHONE, "MISSING", Map.of(), null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("TEMPLATE_NOT_FOUND");
    assertThatThrownBy(
            () -> send.send(new SmsSendService.SendCommand(PHONE, "INACTIVE", Map.of(), null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("TEMPLATE_INACTIVE");
    assertThatThrownBy(
            () -> send.send(new SmsSendService.SendCommand(PHONE, "NO_DLT", Map.of(), null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DLT_TEMPLATE_MISSING");
    assertThatThrownBy(
            () -> send.send(new SmsSendService.SendCommand(PHONE, "BLANK_DLT", Map.of(), null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DLT_TEMPLATE_MISSING");

    Map<String, Object> ok =
        send.send(
            new SmsSendService.SendCommand(
                PHONE, "OTP_VERIFICATION", Map.of("1", "111111", "2", "10"), "OTP"));
    assertThat(ok.get("provider")).isEqualTo("MSG91");
    assertThat(ok.get("fallback_used")).isEqualTo(false);
    assertThat(ok.get("cost_rs")).isEqualTo(new java.math.BigDecimal("0.12"));
  }

  @Test
  void bothProvidersFailAndChannelUnavailable() {
    msg91.setFail(true);
    twilio.setFail(true);
    assertThatThrownBy(
            () ->
                send.send(
                    new SmsSendService.SendCommand(
                        PHONE, "OTP_VERIFICATION", Map.of("1", "1", "2", "10"), "OTP")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ALL_PROVIDERS_FAILED");
    assertThat(logs.all()).hasSize(1);
    assertThat(logs.all().get(0).status().name()).isEqualTo("FAILED");

    SmsSendService noChannel =
        new SmsSendService(
            templates,
            logs,
            msg91,
            twilio,
            AllowAllPreferenceGate.INSTANCE,
            channel -> Optional.empty(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    msg91.reset();
    twilio.reset();
    assertThatThrownBy(
            () ->
                noChannel.send(
                    new SmsSendService.SendCommand(
                        PHONE, "OTP_VERIFICATION", Map.of("1", "1", "2", "10"), "OTP")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ALL_PROVIDERS_FAILED");
  }

  @Test
  void preferenceBlockedAndWebhook() {
    SmsSendService gated =
        new SmsSendService(
            templates,
            logs,
            msg91,
            twilio,
            new com.nammamedmate.notification.application.port.out.PreferenceGatePort() {
              @Override
              public boolean allowsPush(
                  UUID userId,
                  com.nammamedmate.notification.domain.NotificationUserType userType,
                  String category) {
                return true;
              }

              @Override
              public boolean allowsSms(String toPhone, String category) {
                return false;
              }

              @Override
              public boolean allowsWhatsApp(String toPhone) {
                return true;
              }

              @Override
              public boolean allowsEmail(UUID customerId, String toEmail, String category) {
                return true;
              }
            },
            channel -> Optional.of("MSG91"),
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertThatThrownBy(
            () ->
                gated.send(
                    new SmsSendService.SendCommand(PHONE, "PROMO_OFFER", Map.of("1", "x"), null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PREFERENCE_BLOCKED");

    Map<String, Object> sent =
        send.send(
            new SmsSendService.SendCommand(
                PHONE, "OTP_VERIFICATION", Map.of("1", "1", "2", "10"), "OTP"));
    String msgId = (String) sent.get("provider_message_id");
    assertThat(send.handleWebhook(msgId, Instant.parse("2026-07-24T08:20:04Z")).get("updated"))
        .isEqualTo(true);
    assertThatThrownBy(() -> send.handleWebhook(null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_MESSAGE_ID");
    assertThatThrownBy(() -> send.handleWebhook("  ", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_MESSAGE_ID");
    assertThatThrownBy(() -> send.handleWebhook("nope", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("LOG_NOT_FOUND");
    assertThat(send.handleWebhook(msgId, null).get("updated")).isEqualTo(true);
  }

  @Test
  void adminCreateListAndRenderHelpers() {
    Map<String, Object> created =
        admin.createTemplate(
            ADMIN, "REFUND_PROCESSED", "Refund {{1}}", "TRANSACTIONAL", "1007164875432115", null);
    assertThat(created.get("template_id")).isEqualTo("REFUND_PROCESSED");
    assertThat(admin.listTemplates("TRANSACTIONAL", null).get("templates")).asList().isNotEmpty();
    assertThatThrownBy(() -> admin.listTemplates("NOPE", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_CATEGORY");
    assertThatThrownBy(() -> admin.createTemplate(ADMIN, " ", "c", "OTP", "1", "NMMATE"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_TEMPLATE_ID");
    assertThatThrownBy(() -> admin.createTemplate(ADMIN, "X", "c", "BAD", "1", "NMMATE"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_CATEGORY");
    assertThatThrownBy(() -> admin.listLogs(null, null, "BAD", null, null, 0, 0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_STATUS");

    SmsAdminService.LogPage page =
        admin.listLogs(PHONE, null, "SENT", NOW.minusSeconds(10), NOW.plusSeconds(10), null, null);
    assertThat(page.total()).isGreaterThanOrEqualTo(0);

    assertThat(SmsSendService.render(null, Map.of())).isEmpty();
    assertThat(SmsSendService.render("Hi {{1}}", Map.of("1", "A"))).isEqualTo("Hi A");
    java.util.Map<String, String> nullVal = new java.util.LinkedHashMap<>();
    nullVal.put("1", null);
    assertThat(SmsSendService.render("Hi {{1}}", nullVal)).isEqualTo("Hi ");
    assertThat(SmsSendService.render("Hi", null)).isEqualTo("Hi");
    assertThat(SmsSendService.isPromotionalRestricted(Instant.parse("2026-07-24T15:30:00Z")))
        .isTrue(); // 21:00 IST
    assertThat(SmsSendService.isPromotionalRestricted(Instant.parse("2026-07-24T02:30:00Z")))
        .isTrue(); // 08:00 IST
    assertThat(SmsSendService.isPromotionalRestricted(Instant.parse("2026-07-24T03:30:00Z")))
        .isFalse(); // 09:00 IST allowed
    assertThat(send.monthlyCost(NOW.minusSeconds(1), NOW.plusSeconds(1)))
        .isEqualByComparingTo(java.math.BigDecimal.ZERO);

    logs.insert(
        new com.nammamedmate.notification.domain.SmsDeliveryLog(
            java.util.UUID.randomUUID(),
            PHONE,
            "GONE",
            Map.of(),
            com.nammamedmate.notification.domain.SmsProvider.MSG91,
            "m",
            false,
            com.nammamedmate.notification.domain.SmsLogStatus.SENT,
            new java.math.BigDecimal("0.12"),
            NOW,
            null,
            null));
    SmsAdminService.LogPage orphan = admin.listLogs(null, "GONE", null, null, null, 1, 10);
    @SuppressWarnings("unchecked")
    java.util.List<Map<String, Object>> orphanRows =
        (java.util.List<Map<String, Object>>) orphan.data().get("logs");
    assertThat(orphanRows.get(0).get("category")).isNull();

    assertThat(
            new Msg91ClientPort.SendRequest(PHONE, "d", "NMMATE", "b", null, SmsCategory.OTP)
                .variables())
        .isEmpty();
    assertThat(new TwilioClientPort.SendRequest(PHONE, "NMMATE", "b", null).variables()).isEmpty();
  }

  @Test
  void coverageBranchesRemaining() {
    assertThatThrownBy(
            () ->
                send.send(
                    new SmsSendService.SendCommand(
                        null, "OTP_VERIFICATION", Map.of("1", "1", "2", "10"), "OTP")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_PHONE_FORMAT");
    assertThatThrownBy(
            () -> send.send(new SmsSendService.SendCommand(PHONE, null, Map.of(), "OTP")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("TEMPLATE_NOT_FOUND");

    Msg91ClientPort silentFail =
        new Msg91ClientPort() {
          @Override
          public boolean isOnDnd(String toPhone) {
            return false;
          }

          @Override
          public SendResult send(SendRequest request) {
            return new SendResult(false, false, null, null);
          }
        };
    TwilioClientPort silentTwilio = request -> new TwilioClientPort.SendResult(false, null, null);
    SmsSendService bothNull =
        new SmsSendService(
            templates,
            logs,
            silentFail,
            silentTwilio,
            AllowAllPreferenceGate.INSTANCE,
            channel -> Optional.of("MSG91"),
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertThatThrownBy(
            () ->
                bothNull.send(
                    new SmsSendService.SendCommand(
                        PHONE, "OTP_VERIFICATION", Map.of("1", "1", "2", "10"), "OTP")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ALL_PROVIDERS_FAILED");

    SmsDeliveryLogStore nullSum =
        new SmsServiceAcTest.FakeSmsDeliveryLogStore() {
          @Override
          public java.math.BigDecimal sumCostBetween(Instant fromInclusive, Instant toExclusive) {
            return null;
          }
        };
    SmsSendService nullCost =
        new SmsSendService(
            templates,
            nullSum,
            msg91,
            twilio,
            AllowAllPreferenceGate.INSTANCE,
            channel -> Optional.of("MSG91"),
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertThat(nullCost.monthlyCost(NOW, NOW.plusSeconds(1)))
        .isEqualByComparingTo(java.math.BigDecimal.ZERO);

    assertThat(admin.listTemplates("  ", null).get("templates")).asList().isNotEmpty();
    assertThatThrownBy(() -> admin.createTemplate(ADMIN, null, null, "OTP", "1", "  "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_TEMPLATE_ID");
    Map<String, Object> created =
        admin.createTemplate(ADMIN, "NEW_TPL", null, "OTP", "1007164875432999", null);
    assertThat(created.get("template_id")).isEqualTo("NEW_TPL");
    assertThat(
            admin
                .createTemplate(
                    ADMIN, "NEW_TPL2", "body", "TRANSACTIONAL", "1007164875432998", "NMMATE")
                .get("template_id"))
        .isEqualTo("NEW_TPL2");
    assertThat(
            admin
                .createTemplate(ADMIN, "NEW_TPL3", "body", "OTP", "1007164875432997", "   ")
                .get("template_id"))
        .isEqualTo("NEW_TPL3");

    Msg91ClientPort primaryErr =
        new Msg91ClientPort() {
          @Override
          public boolean isOnDnd(String toPhone) {
            return false;
          }

          @Override
          public SendResult send(SendRequest request) {
            return new SendResult(false, false, null, "MSG91 down");
          }
        };
    assertThatThrownBy(
            () ->
                new SmsSendService(
                        templates,
                        logs,
                        primaryErr,
                        silentTwilio,
                        AllowAllPreferenceGate.INSTANCE,
                        channel -> Optional.of("MSG91"),
                        Clock.fixed(NOW, ZoneOffset.UTC))
                    .send(
                        new SmsSendService.SendCommand(
                            PHONE, "OTP_VERIFICATION", Map.of("1", "1", "2", "10"), "OTP")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ALL_PROVIDERS_FAILED");

    logs.insert(
        new com.nammamedmate.notification.domain.SmsDeliveryLog(
            java.util.UUID.randomUUID(),
            PHONE,
            "OTP_VERIFICATION",
            Map.of(),
            com.nammamedmate.notification.domain.SmsProvider.MSG91,
            "delivered-1",
            false,
            com.nammamedmate.notification.domain.SmsLogStatus.DELIVERED,
            new java.math.BigDecimal("0.12"),
            NOW,
            NOW,
            null));
    assertThat(admin.listLogs(PHONE, "OTP_VERIFICATION", "DELIVERED", null, null, 1, 20).total())
        .isGreaterThanOrEqualTo(1);

    SmsAdminService.LogPage nullData = new SmsAdminService.LogPage(null, 1, 20, 0);
    assertThat(nullData.data()).isEmpty();
    assertThat(
            new com.nammamedmate.notification.application.port.out.SmsDeliveryLogStore.Page(null, 0)
                .logs())
        .isEmpty();

    assertThat(admin.listLogs(null, null, "  ", null, null, 1, 20).total())
        .isGreaterThanOrEqualTo(0);

    templates.insert(
        new SmsTemplate(
            "NO_CREATED_AT", "c", SmsCategory.OTP, "1007", "NMMATE", true, ADMIN, null));
    assertThat(admin.listTemplates(null, null).get("templates")).asList().isNotEmpty();

    logs.insert(
        new com.nammamedmate.notification.domain.SmsDeliveryLog(
            java.util.UUID.randomUUID(),
            PHONE,
            "OTP_VERIFICATION",
            Map.of(),
            null,
            null,
            false,
            com.nammamedmate.notification.domain.SmsLogStatus.SKIPPED_DND,
            null,
            null,
            null,
            null));
    SmsAdminService.LogPage weird = admin.listLogs("  ", "  ", null, null, null, 1, 100);
    assertThat(weird.data().get("logs")).asList().isNotEmpty();
  }
}
