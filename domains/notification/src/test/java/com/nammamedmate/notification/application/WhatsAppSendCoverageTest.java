package com.nammamedmate.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.notification.adapter.out.client.StubMetaWhatsAppClient;
import com.nammamedmate.notification.application.port.out.PreferenceGatePort;
import com.nammamedmate.notification.domain.NotificationUserType;
import com.nammamedmate.notification.domain.WhatsAppCategory;
import com.nammamedmate.notification.domain.WhatsAppTemplate;
import com.nammamedmate.notification.domain.WhatsAppTemplateStatus;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WhatsAppSendCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T08:22:00Z");
  private static final String PHONE = "+919876543210";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private WhatsAppServiceAcTest.FakeWhatsAppTemplateStore templates;
  private WhatsAppServiceAcTest.FakeWhatsAppDeliveryLogStore logs;
  private WhatsAppServiceAcTest.FakeWhatsAppOptoutStore optouts;
  private WhatsAppServiceAcTest.FakeWhatsAppSessionStore sessions;
  private StubMetaWhatsAppClient meta;
  private WhatsAppSendService send;
  private WhatsAppAdminService admin;
  private Clock clock;

  @BeforeEach
  void setUp() {
    clock = Clock.fixed(NOW, ZoneOffset.UTC);
    templates = new WhatsAppServiceAcTest.FakeWhatsAppTemplateStore();
    logs = new WhatsAppServiceAcTest.FakeWhatsAppDeliveryLogStore();
    optouts = new WhatsAppServiceAcTest.FakeWhatsAppOptoutStore();
    sessions = new WhatsAppServiceAcTest.FakeWhatsAppSessionStore();
    meta = new StubMetaWhatsAppClient();
    templates.insert(
        new WhatsAppTemplate(
            Ids.newId(),
            "ORDER_CONFIRMED",
            WhatsAppCategory.UTILITY,
            "en",
            WhatsAppTemplateStatus.APPROVED,
            "Hi {{1}}, order {{2}}.",
            null,
            null,
            List.of(),
            "meta",
            null,
            NOW,
            NOW,
            null));
    templates.insert(
        new WhatsAppTemplate(
            Ids.newId(),
            "EXISTING",
            WhatsAppCategory.UTILITY,
            "en",
            WhatsAppTemplateStatus.APPROVED,
            "x",
            null,
            null,
            List.of(),
            "meta",
            null,
            NOW,
            NOW,
            null));
    send =
        new WhatsAppSendService(
            templates,
            logs,
            optouts,
            sessions,
            meta,
            AllowAllPreferenceGate.INSTANCE,
            MAPPER,
            clock);
    admin = new WhatsAppAdminService(templates, logs, meta, clock);
  }

  @Test
  void invalidPhoneAndMissingTemplate() {
    assertThatThrownBy(
            () ->
                send.send(
                    new WhatsAppSendService.SendCommand(
                        "9876543210", "ORDER_CONFIRMED", "en", List.of())))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_PHONE_FORMAT");
    assertThatThrownBy(
            () -> send.send(new WhatsAppSendService.SendCommand(PHONE, "MISSING", "en", List.of())))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("TEMPLATE_NOT_FOUND");
  }

  @Test
  void componentMismatchAndInvalidLanguage() {
    assertThatThrownBy(
            () ->
                send.send(
                    new WhatsAppSendService.SendCommand(
                        PHONE,
                        "ORDER_CONFIRMED",
                        "en",
                        List.of(
                            Map.of(
                                "type",
                                "body",
                                "parameters",
                                List.of(Map.of("type", "text", "text", "only-one")))))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("COMPONENT_MISMATCH");
    assertThatThrownBy(
            () ->
                send.send(
                    new WhatsAppSendService.SendCommand(
                        PHONE,
                        "ORDER_CONFIRMED",
                        "EN",
                        List.of(
                            Map.of(
                                "type",
                                "body",
                                "parameters",
                                List.of(
                                    Map.of("type", "text", "text", "a"),
                                    Map.of("type", "text", "text", "b")))))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_LANGUAGE");
  }

  @Test
  void metaSendFailureAndPreferenceBlock() {
    meta.setFailSend(true);
    assertThatThrownBy(
            () ->
                send.send(
                    new WhatsAppSendService.SendCommand(
                        PHONE,
                        "ORDER_CONFIRMED",
                        "en",
                        List.of(
                            Map.of(
                                "type",
                                "body",
                                "parameters",
                                List.of(
                                    Map.of("type", "text", "text", "a"),
                                    Map.of("type", "text", "text", "b")))))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("META_API_UNAVAILABLE");

    WhatsAppSendService blocked =
        new WhatsAppSendService(
            templates,
            logs,
            optouts,
            sessions,
            meta,
            new PreferenceGatePort() {
              @Override
              public boolean allowsPush(
                  UUID userId, NotificationUserType userType, String category) {
                return true;
              }

              @Override
              public boolean allowsSms(String toPhone, String category) {
                return true;
              }

              @Override
              public boolean allowsWhatsApp(String toPhone) {
                return false;
              }

              @Override
              public boolean allowsEmail(UUID customerId, String toEmail, String category) {
                return true;
              }
            },
            MAPPER,
            clock);
    assertThatThrownBy(
            () ->
                blocked.send(
                    new WhatsAppSendService.SendCommand(PHONE, "ORDER_CONFIRMED", "en", List.of())))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RECIPIENT_OPTED_OUT");
  }

  @Test
  void webhookBranchesAndSessionWindow() throws Exception {
    Map<String, Object> sent =
        send.send(
            new WhatsAppSendService.SendCommand(
                PHONE,
                "ORDER_CONFIRMED",
                null,
                List.of(
                    Map.of(
                        "type",
                        "body",
                        "parameters",
                        List.of(
                            Map.of("type", "text", "text", "a"),
                            Map.of("type", "text", "text", "b"))))));
    String waId = (String) sent.get("wa_message_id");

    String failed =
        """
        {"object":"whatsapp_business_account","entry":[{"changes":[{"value":{"statuses":[
          {"id":"%s","status":"failed","timestamp":"bad","errors":[{"code":"131","title":"gone"}]}
        ]}}]}]}
        """
            .formatted(waId);
    byte[] failedBody = failed.getBytes(StandardCharsets.UTF_8);
    send.handleWebhook(meta.sign(failedBody), failedBody);
    assertThat(logs.byWaId(waId).status().name()).isEqualTo("FAILED");

    String zeroOpt =
        """
        {"object":"whatsapp_business_account","entry":[{"changes":[{"value":{"messages":[
          {"from":"+919811122233","timestamp":"1721808500","text":{"body":"0"}}
        ]}}]}]}
        """;
    byte[] zeroBody = zeroOpt.getBytes(StandardCharsets.UTF_8);
    send.handleWebhook(meta.sign(zeroBody), zeroBody);
    assertThat(optouts.isActivelyOptedOut("+919811122233")).isTrue();

    byte[] empty = "{}".getBytes(StandardCharsets.UTF_8);
    assertThat(send.handleWebhook(meta.sign(empty), empty).get("processed")).isEqualTo(true);
    assertThat(send.handleWebhook(meta.sign(new byte[0]), null).get("processed")).isEqualTo(true);
    assertThat(send.handleWebhook(meta.sign(new byte[0]), new byte[0]).get("processed"))
        .isEqualTo(true);

    String noEntry = "{\"object\":\"x\",\"entry\":\"nope\"}";
    byte[] noEntryBody = noEntry.getBytes(StandardCharsets.UTF_8);
    assertThat(send.handleWebhook(meta.sign(noEntryBody), noEntryBody).get("processed"))
        .isEqualTo(true);

    assertThat(send.withinCustomerSession(PHONE)).isFalse();
    sessions.upsertCustomerMessage(PHONE, NOW.minusSeconds(60));
    assertThat(send.withinCustomerSession(PHONE)).isTrue();
    sessions.upsertCustomerMessage(PHONE, NOW.minusSeconds(25 * 60 * 60));
    assertThat(send.withinCustomerSession(PHONE)).isFalse();

    assertThat(WhatsAppSendService.toE164(null)).isEmpty();
    assertThat(WhatsAppSendService.toE164("  ")).isEmpty();
    assertThat(WhatsAppSendService.toE164("+91")).isEqualTo("+91");
    assertThat(WhatsAppSendService.countPlaceholders(null)).isZero();
    assertThat(WhatsAppSendService.countPlaceholders("")).isZero();

    com.fasterxml.jackson.databind.ObjectMapper om =
        new com.fasterxml.jackson.databind.ObjectMapper();
    assertThat(WhatsAppSendService.text(null, "x")).isNull();
    assertThat(WhatsAppSendService.text(om.nullNode(), "x")).isNull();
    assertThat(WhatsAppSendService.text(om.missingNode(), "x")).isNull();
    assertThat(WhatsAppSendService.text(om.createObjectNode(), "missing")).isNull();
    assertThat(WhatsAppSendService.text(om.readTree("{\"a\":null}"), "a")).isNull();
    assertThat(WhatsAppSendService.text(om.readTree("{\"a\":\"  \"}"), "a")).isNull();
    assertThat(WhatsAppSendService.text(om.readTree("{\"a\":\"ok\"}"), "a")).isEqualTo("ok");
    assertThat(WhatsAppSendService.text(om.readTree("{\"a\":{}}"), "a")).isNull();
    assertThat(send.epochOrNowForTest(null)).isEqualTo(NOW);
    assertThat(send.epochOrNowForTest("  ")).isEqualTo(NOW);
    assertThat(send.epochOrNowForTest("1721808128")).isEqualTo(Instant.ofEpochSecond(1721808128L));
    assertThat(send.epochOrNowForTest("bad")).isEqualTo(NOW);
  }

  @Test
  void adminValidationAndFilters() {
    assertThatThrownBy(() -> admin.submitTemplate("", "UTILITY", "en", "b", null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_TEMPLATE_NAME");
    assertThatThrownBy(
            () -> admin.submitTemplate("EXISTING", "UTILITY", "en", "b", null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("TEMPLATE_NAME_EXISTS");
    assertThatThrownBy(() -> admin.submitTemplate("NEW_ONE", "NOPE", "en", "b", null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_CATEGORY");
    assertThatThrownBy(
            () -> admin.submitTemplate("NEW_TWO", "UTILITY", "EN", "b", null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_LANGUAGE");

    meta.setFailSubmit(true);
    assertThatThrownBy(
            () -> admin.submitTemplate("NEW_THREE", "UTILITY", "en", "b", null, null, List.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("META_API_UNAVAILABLE");
    meta.setFailSubmit(false);

    assertThatThrownBy(() -> admin.listTemplates("BAD", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_CATEGORY");
    assertThatThrownBy(() -> admin.listTemplates(null, "BAD"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_STATUS");
    assertThat(admin.listTemplates("UTILITY", "APPROVED").get("templates")).isNotNull();

    assertThatThrownBy(() -> admin.listLogs(null, null, "BAD", null, null, 0, 0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_STATUS");
    WhatsAppAdminService.LogPage page =
        admin.listLogs(
            PHONE,
            "ORDER_CONFIRMED",
            "SENT",
            NOW.minusSeconds(10),
            NOW.plusSeconds(10),
            null,
            null);
    assertThat(page.page()).isEqualTo(1);
    assertThat(page.limit()).isEqualTo(20);
  }
}
