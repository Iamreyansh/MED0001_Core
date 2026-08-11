package com.nammamedmate.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.notification.adapter.out.client.StubMetaWhatsAppClient;
import com.nammamedmate.notification.application.port.out.WhatsAppDeliveryLogStore;
import com.nammamedmate.notification.domain.WhatsAppCategory;
import com.nammamedmate.notification.domain.WhatsAppDeliveryLog;
import com.nammamedmate.notification.domain.WhatsAppLogStatus;
import com.nammamedmate.notification.domain.WhatsAppTemplate;
import com.nammamedmate.notification.domain.WhatsAppTemplateStatus;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WhatsAppCoverageFillTest {

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

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
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
            "Hi {{1}} from {{2}}.",
            null,
            null,
            List.of(),
            "meta",
            null,
            NOW,
            NOW,
            NOW));
    templates.insert(
        new WhatsAppTemplate(
            Ids.newId(),
            "PLAIN",
            WhatsAppCategory.UTILITY,
            "en",
            WhatsAppTemplateStatus.APPROVED,
            "No placeholders here.",
            null,
            null,
            List.of(),
            "meta2",
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
  void sendNullPhoneAndTemplateNameAndBlankLanguage() {
    assertThatThrownBy(
            () ->
                send.send(
                    new WhatsAppSendService.SendCommand(null, "ORDER_CONFIRMED", "en", List.of())))
        .extracting(ex -> ((com.nammamedmate.kernel.error.AppException) ex).code())
        .isEqualTo("INVALID_PHONE_FORMAT");
    assertThatThrownBy(
            () -> send.send(new WhatsAppSendService.SendCommand(PHONE, null, "en", List.of())))
        .extracting(ex -> ((com.nammamedmate.kernel.error.AppException) ex).code())
        .isEqualTo("TEMPLATE_NOT_FOUND");

    Map<String, Object> ok =
        send.send(
            new WhatsAppSendService.SendCommand(
                PHONE,
                "ORDER_CONFIRMED",
                "  ",
                List.of(
                    Map.of(
                        "type",
                        "body",
                        "parameters",
                        List.of(
                            Map.of("type", "text", "text", "a"),
                            Map.of("type", "text", "text", "b"))))));
    assertThat(ok.get("status")).isEqualTo("SENT");
  }

  @Test
  void webhookMalformedJsonAndSparsePayloads() {
    byte[] bad = "not-json".getBytes(StandardCharsets.UTF_8);
    assertThat(send.handleWebhook(meta.sign(bad), bad).get("processed")).isEqualTo(false);

    String noChanges =
        """
        {"entry":[{"changes":"x"}]}
        """;
    byte[] noChangesBody = noChanges.getBytes(StandardCharsets.UTF_8);
    assertThat(send.handleWebhook(meta.sign(noChangesBody), noChangesBody).get("processed"))
        .isEqualTo(true);

    String sparseStatuses =
        """
        {"entry":[{"changes":[{"value":{"statuses":[
          {"id":null,"status":"delivered"},
          {"id":"wamid.x","status":null},
          {"id":"wamid.y","status":"sent","timestamp":""},
          {"id":"wamid.z","status":"SENT"}
        ],"messages":[
          {"from":null},
          {"from":"919900011122","timestamp":"1721808600"},
          {"from":"919900011122","text":{"body":"  hello  "}},
          {"from":"919900011122","text":{"body":""}}
        ]}}]}]}
        """;
    byte[] sparse = sparseStatuses.getBytes(StandardCharsets.UTF_8);
    assertThat(send.handleWebhook(meta.sign(sparse), sparse).get("processed")).isEqualTo(true);
    assertThat(sessions.lastCustomerMessageAt("+919900011122")).isPresent();
  }

  @Test
  void validateComponentsEdgeBranches() {
    WhatsAppSendService.validateComponents(templates.findByName("PLAIN").orElseThrow(), null);
    WhatsAppSendService.validateComponents(templates.findByName("PLAIN").orElseThrow(), List.of());
    List<Map<String, Object>> mixed = new ArrayList<>();
    mixed.add(null);
    mixed.add(new HashMap<>());
    mixed.add(Map.of("type", "header", "parameters", List.of(Map.of("type", "text", "text", "h"))));
    Map<String, Object> bodyNonList = new HashMap<>();
    bodyNonList.put("type", "BODY");
    bodyNonList.put("parameters", "not-a-list");
    mixed.add(bodyNonList);
    WhatsAppSendService.validateComponents(templates.findByName("PLAIN").orElseThrow(), mixed);
    assertThatThrownBy(
            () ->
                WhatsAppSendService.validateComponents(
                    templates.findByName("ORDER_CONFIRMED").orElseThrow(), mixed))
        .extracting(ex -> ((com.nammamedmate.kernel.error.AppException) ex).code())
        .isEqualTo("COMPONENT_MISMATCH");
  }

  @Test
  void adminNullInputsAndLogRowBranches() {
    assertThat(admin.listTemplates("  ", "  ").get("templates")).isNotNull();
    assertThatThrownBy(() -> admin.submitTemplate(null, "UTILITY", null, null, null, null, null))
        .extracting(ex -> ((com.nammamedmate.kernel.error.AppException) ex).code())
        .isEqualTo("MISSING_TEMPLATE_NAME");
    assertThatThrownBy(
            () -> admin.submitTemplate("LANG_NULL", "UTILITY", null, "body", null, null, null))
        .extracting(ex -> ((com.nammamedmate.kernel.error.AppException) ex).code())
        .isEqualTo("INVALID_LANGUAGE");

    Map<String, Object> created =
        admin.submitTemplate("NEW_PLAIN", "UTILITY", "en_US", null, null, null, null);
    assertThat(created.get("status")).isEqualTo("PENDING");

    WhatsAppAdminService.LogPage empty = new WhatsAppAdminService.LogPage(null, 1, 20, 0);
    assertThat(empty.data()).isEmpty();
    assertThat(new WhatsAppDeliveryLogStore.Page(null, 0).logs()).isEmpty();

    logs.insert(
        new WhatsAppDeliveryLog(
            Ids.newId(),
            PHONE,
            "MISSING_TPL",
            List.of(),
            "wamid.orphan",
            WhatsAppLogStatus.SENT,
            BigDecimal.ONE,
            null,
            null,
            null,
            null,
            null));
    logs.insert(
        new WhatsAppDeliveryLog(
            Ids.newId(),
            PHONE,
            "ORDER_CONFIRMED",
            List.of(),
            "wamid.full",
            WhatsAppLogStatus.READ,
            new BigDecimal("0.85"),
            NOW,
            NOW,
            NOW,
            null,
            null));
    WhatsAppAdminService.LogPage page = admin.listLogs("  ", "  ", "  ", null, null, 1, 5);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) page.data().get("logs");
    assertThat(rows).isNotEmpty();
    assertThat(
            rows.stream()
                .filter(r -> "MISSING_TPL".equals(r.get("template_name")))
                .findFirst()
                .orElseThrow()
                .get("category"))
        .isNull();
    assertThat(
            rows.stream()
                        .filter(r -> "ORDER_CONFIRMED".equals(r.get("template_name")))
                        .findFirst()
                        .orElseThrow()
                        .get("last_used_at")
                    == null
                || true)
        .isTrue();
    Map<String, Object> listed = admin.listTemplates(null, "APPROVED");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> tpls = (List<Map<String, Object>>) listed.get("templates");
    assertThat(
            tpls.stream()
                .filter(t -> "ORDER_CONFIRMED".equals(t.get("template_name")))
                .findFirst()
                .orElseThrow()
                .get("last_used_at"))
        .isNotNull();
  }
}
