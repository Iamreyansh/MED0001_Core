package com.nammamedmate.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.notification.adapter.out.client.StubMetaWhatsAppClient;
import com.nammamedmate.notification.application.port.out.WhatsAppDeliveryLogStore;
import com.nammamedmate.notification.application.port.out.WhatsAppOptoutStore;
import com.nammamedmate.notification.application.port.out.WhatsAppSessionStore;
import com.nammamedmate.notification.application.port.out.WhatsAppTemplateStore;
import com.nammamedmate.notification.domain.WhatsAppCategory;
import com.nammamedmate.notification.domain.WhatsAppDeliveryLog;
import com.nammamedmate.notification.domain.WhatsAppLogStatus;
import com.nammamedmate.notification.domain.WhatsAppOptout;
import com.nammamedmate.notification.domain.WhatsAppOptoutSource;
import com.nammamedmate.notification.domain.WhatsAppTemplate;
import com.nammamedmate.notification.domain.WhatsAppTemplateStatus;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WhatsAppServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T08:22:00Z");
  private static final String PHONE = "+919876543210";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private FakeWhatsAppTemplateStore templates;
  private FakeWhatsAppDeliveryLogStore logs;
  private FakeWhatsAppOptoutStore optouts;
  private FakeWhatsAppSessionStore sessions;
  private StubMetaWhatsAppClient meta;
  private WhatsAppSendService send;
  private WhatsAppAdminService admin;

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    templates = new FakeWhatsAppTemplateStore();
    logs = new FakeWhatsAppDeliveryLogStore();
    optouts = new FakeWhatsAppOptoutStore();
    sessions = new FakeWhatsAppSessionStore();
    meta = new StubMetaWhatsAppClient();
    seedTemplates();
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
  void ac001_optedOutReturns422WithoutMetaCall() {
    optouts.upsertActive(Ids.newId(), PHONE, WhatsAppOptoutSource.WA_REPLY, NOW);
    assertThatThrownBy(() -> send.send(orderConfirmedCmd()))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RECIPIENT_OPTED_OUT");
    assertThat(meta.sendCallCount()).isZero();
  }

  @Test
  void ac002_badWebhookSignatureReturns403() {
    byte[] body = "{\"object\":\"whatsapp_business_account\"}".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(() -> send.handleWebhook("sha256=deadbeef", body))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException ae = (AppException) ex;
              assertThat(ae.code()).isEqualTo("INVALID_SIGNATURE");
              assertThat(ae.httpStatus()).isEqualTo(403);
            });
  }

  @Test
  void ac003_deliveredAndReadWebhooksUpdateTimestamps() {
    Map<String, Object> sent = send.send(orderConfirmedCmd());
    String waId = (String) sent.get("wa_message_id");
    String deliveredJson =
        """
        {"object":"whatsapp_business_account","entry":[{"changes":[{"value":{"statuses":[
          {"id":"%s","recipient_id":"919876543210","status":"delivered","timestamp":"1721808128"}
        ]},"field":"messages"}]}]}
        """
            .formatted(waId);
    byte[] deliveredBody = deliveredJson.getBytes(StandardCharsets.UTF_8);
    send.handleWebhook(meta.sign(deliveredBody), deliveredBody);
    assertThat(logs.byWaId(waId).status()).isEqualTo(WhatsAppLogStatus.DELIVERED);
    assertThat(logs.byWaId(waId).deliveredAt()).isEqualTo(Instant.ofEpochSecond(1721808128L));

    String readJson =
        """
        {"object":"whatsapp_business_account","entry":[{"changes":[{"value":{"statuses":[
          {"id":"%s","recipient_id":"919876543210","status":"read","timestamp":"1721808341"}
        ]},"field":"messages"}]}]}
        """
            .formatted(waId);
    byte[] readBody = readJson.getBytes(StandardCharsets.UTF_8);
    send.handleWebhook(meta.sign(readBody), readBody);
    assertThat(logs.byWaId(waId).status()).isEqualTo(WhatsAppLogStatus.READ);
    assertThat(logs.byWaId(waId).readAt()).isEqualTo(Instant.ofEpochSecond(1721808341L));
  }

  @Test
  void ac004_pendingOrRejectedTemplateNotApproved() {
    assertThatThrownBy(
            () ->
                send.send(
                    new WhatsAppSendService.SendCommand(
                        PHONE, "KYC_PENDING_REVIEW", "en", bodyParams("Ravi"))))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("TEMPLATE_NOT_APPROVED");
    assertThatThrownBy(
            () ->
                send.send(
                    new WhatsAppSendService.SendCommand(
                        PHONE, "PROMO_SUMMER_SALE", "en", List.of())))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("TEMPLATE_NOT_APPROVED");
    assertThat(meta.sendCallCount()).isZero();
  }

  @Test
  void ac005_rejectionReasonNullWhenApproved() {
    Map<String, Object> listed = admin.listTemplates(null, null);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) listed.get("templates");
    Map<String, Object> approved =
        rows.stream()
            .filter(r -> "ORDER_CONFIRMED".equals(r.get("template_name")))
            .findFirst()
            .orElseThrow();
    Map<String, Object> rejected =
        rows.stream()
            .filter(r -> "PROMO_SUMMER_SALE".equals(r.get("template_name")))
            .findFirst()
            .orElseThrow();
    assertThat(approved.get("rejection_reason")).isNull();
    assertThat(rejected.get("rejection_reason"))
        .isEqualTo("Content policy violation: discount percentage claims require proof");
  }

  @Test
  void ac006_submitReorderReminderReturnsPending() {
    Map<String, Object> data =
        admin.submitTemplate(
            "REORDER_REMINDER",
            "UTILITY",
            "en",
            "Hi {{1}}, reorder {{2}}.",
            Map.of("format", "TEXT", "text", "Medicine Refill Reminder"),
            "Reply STOP to opt out",
            List.of(
                Map.of(
                    "type",
                    "URL",
                    "text",
                    "Reorder Now",
                    "url",
                    "https://app.nammamedmate.in/reorder/{{1}}")));
    assertThat(data.get("template_name")).isEqualTo("REORDER_REMINDER");
    assertThat(data.get("status")).isEqualTo("PENDING");
    assertThat(data.get("estimated_review_days")).isEqualTo(2);
    assertThat(templates.findByName("REORDER_REMINDER")).isPresent();
    assertThat(templates.findByName("REORDER_REMINDER").orElseThrow().status())
        .isEqualTo(WhatsAppTemplateStatus.PENDING);
    assertThat(meta.submitCallCount()).isEqualTo(1);
  }

  @Test
  void ac007_stopReplyCreatesOptout() {
    String stopJson =
        """
        {"object":"whatsapp_business_account","entry":[{"changes":[{"value":{"messages":[
          {"from":"919876543210","timestamp":"1721808400","text":{"body":"STOP"}}
        ]},"field":"messages"}]}]}
        """;
    byte[] body = stopJson.getBytes(StandardCharsets.UTF_8);
    send.handleWebhook(meta.sign(body), body);
    assertThat(optouts.isActivelyOptedOut(PHONE)).isTrue();
    assertThat(optouts.findActiveByPhone(PHONE).orElseThrow().source())
        .isEqualTo(WhatsAppOptoutSource.WA_REPLY);
    assertThat(sessions.lastCustomerMessageAt(PHONE)).contains(Instant.ofEpochSecond(1721808400L));
  }

  @Test
  void ac008_costRsUtility085Marketing200() {
    send.send(orderConfirmedCmd());
    send.send(
        new WhatsAppSendService.SendCommand(
            PHONE, "REFERRAL_REWARD", "en", bodyParams("Ravi", "50")));
    WhatsAppAdminService.LogPage page = admin.listLogs(null, null, null, null, null, 1, 20);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) page.data().get("logs");
    Map<String, Object> utility =
        rows.stream()
            .filter(r -> "ORDER_CONFIRMED".equals(r.get("template_name")))
            .findFirst()
            .orElseThrow();
    Map<String, Object> marketing =
        rows.stream()
            .filter(r -> "REFERRAL_REWARD".equals(r.get("template_name")))
            .findFirst()
            .orElseThrow();
    assertThat((BigDecimal) utility.get("cost_rs")).isEqualByComparingTo("0.85");
    assertThat((BigDecimal) marketing.get("cost_rs")).isEqualByComparingTo("2.00");
  }

  private WhatsAppSendService.SendCommand orderConfirmedCmd() {
    return new WhatsAppSendService.SendCommand(
        PHONE,
        "ORDER_CONFIRMED",
        "en",
        bodyParams("Ravi Kumar", "Apollo", "Rs 504.00", "45 minutes"));
  }

  private static List<Map<String, Object>> bodyParams(String... values) {
    List<Map<String, Object>> params = new ArrayList<>();
    for (String v : values) {
      params.add(Map.of("type", "text", "text", v));
    }
    return List.of(Map.of("type", "body", "parameters", params));
  }

  private void seedTemplates() {
    templates.insert(
        new WhatsAppTemplate(
            UUID.fromString("b1000001-0000-4000-8000-000000000001"),
            "ORDER_CONFIRMED",
            WhatsAppCategory.UTILITY,
            "en",
            WhatsAppTemplateStatus.APPROVED,
            "Hi {{1}}, your order from {{2}} for {{3}} has been confirmed. Estimated delivery: {{4}}.",
            Map.of("format", "TEXT", "text", "Order Confirmed"),
            "Namma MedMate",
            List.of(Map.of("type", "URL", "text", "Track Order")),
            "meta_tpl_order_confirmed",
            null,
            NOW,
            NOW,
            null));
    templates.insert(
        new WhatsAppTemplate(
            UUID.fromString("b1000001-0000-4000-8000-000000000002"),
            "REFERRAL_REWARD",
            WhatsAppCategory.MARKETING,
            "en",
            WhatsAppTemplateStatus.APPROVED,
            "Hi {{1}}, you earned Rs {{2}} referral reward. Tap to claim!",
            null,
            "Reply STOP to opt out",
            List.of(),
            "meta_tpl_referral_reward",
            null,
            NOW,
            NOW,
            null));
    templates.insert(
        new WhatsAppTemplate(
            UUID.fromString("b1000001-0000-4000-8000-000000000003"),
            "PROMO_SUMMER_SALE",
            WhatsAppCategory.MARKETING,
            "en",
            WhatsAppTemplateStatus.REJECTED,
            "Get 20% off all vitamins this week! Use code SUMMER20.",
            null,
            null,
            List.of(),
            null,
            "Content policy violation: discount percentage claims require proof",
            NOW,
            null,
            null));
    templates.insert(
        new WhatsAppTemplate(
            UUID.fromString("b1000001-0000-4000-8000-000000000004"),
            "KYC_PENDING_REVIEW",
            WhatsAppCategory.UTILITY,
            "en",
            WhatsAppTemplateStatus.PENDING,
            "Hi {{1}}, your KYC documents are under review.",
            null,
            null,
            List.of(),
            null,
            null,
            NOW,
            null,
            null));
  }

  static final class FakeWhatsAppTemplateStore implements WhatsAppTemplateStore {
    private final ConcurrentHashMap<String, WhatsAppTemplate> byName = new ConcurrentHashMap<>();

    @Override
    public Optional<WhatsAppTemplate> findByName(String templateName) {
      return Optional.ofNullable(byName.get(templateName));
    }

    @Override
    public boolean exists(String templateName) {
      return byName.containsKey(templateName);
    }

    @Override
    public void insert(WhatsAppTemplate template) {
      byName.put(template.templateName(), template);
    }

    @Override
    public void touchLastUsed(String templateName, Instant at) {
      WhatsAppTemplate t = byName.get(templateName);
      if (t != null) {
        byName.put(
            templateName,
            new WhatsAppTemplate(
                t.id(),
                t.templateName(),
                t.category(),
                t.language(),
                t.status(),
                t.bodyText(),
                t.header(),
                t.footerText(),
                t.buttons(),
                t.metaTemplateId(),
                t.rejectionReason(),
                t.submittedAt(),
                t.approvedAt(),
                at));
      }
    }

    @Override
    public List<WhatsAppTemplate> list(WhatsAppCategory category, WhatsAppTemplateStatus status) {
      return byName.values().stream()
          .filter(t -> category == null || t.category() == category)
          .filter(t -> status == null || t.status() == status)
          .collect(Collectors.toCollection(ArrayList::new));
    }
  }

  static final class FakeWhatsAppDeliveryLogStore implements WhatsAppDeliveryLogStore {
    private final List<WhatsAppDeliveryLog> rows = new ArrayList<>();

    WhatsAppDeliveryLog byWaId(String waId) {
      return rows.stream().filter(l -> waId.equals(l.waMessageId())).findFirst().orElseThrow();
    }

    @Override
    public void insert(WhatsAppDeliveryLog log) {
      rows.add(log);
    }

    @Override
    public Optional<WhatsAppDeliveryLog> findById(UUID id) {
      return rows.stream().filter(l -> l.id().equals(id)).findFirst();
    }

    @Override
    public Optional<WhatsAppDeliveryLog> findByWaMessageId(String waMessageId) {
      return rows.stream().filter(l -> waMessageId.equals(l.waMessageId())).findFirst();
    }

    @Override
    public boolean markDelivered(String waMessageId, Instant deliveredAt) {
      for (int i = 0; i < rows.size(); i++) {
        WhatsAppDeliveryLog log = rows.get(i);
        if (!waMessageId.equals(log.waMessageId())) {
          continue;
        }
        WhatsAppLogStatus st =
            log.status() == WhatsAppLogStatus.READ
                ? WhatsAppLogStatus.READ
                : WhatsAppLogStatus.DELIVERED;
        rows.set(
            i,
            new WhatsAppDeliveryLog(
                log.id(),
                log.toPhone(),
                log.templateName(),
                log.components(),
                log.waMessageId(),
                st,
                log.costRs(),
                log.sentAt(),
                log.deliveredAt() == null ? deliveredAt : log.deliveredAt(),
                log.readAt(),
                log.errorCode(),
                log.errorMessage()));
        return true;
      }
      return false;
    }

    @Override
    public boolean markRead(String waMessageId, Instant readAt) {
      for (int i = 0; i < rows.size(); i++) {
        WhatsAppDeliveryLog log = rows.get(i);
        if (!waMessageId.equals(log.waMessageId())) {
          continue;
        }
        rows.set(
            i,
            new WhatsAppDeliveryLog(
                log.id(),
                log.toPhone(),
                log.templateName(),
                log.components(),
                log.waMessageId(),
                WhatsAppLogStatus.READ,
                log.costRs(),
                log.sentAt(),
                log.deliveredAt() == null ? readAt : log.deliveredAt(),
                log.readAt() == null ? readAt : log.readAt(),
                log.errorCode(),
                log.errorMessage()));
        return true;
      }
      return false;
    }

    @Override
    public boolean markFailed(String waMessageId, String errorCode, String errorMessage) {
      for (int i = 0; i < rows.size(); i++) {
        WhatsAppDeliveryLog log = rows.get(i);
        if (!waMessageId.equals(log.waMessageId())) {
          continue;
        }
        rows.set(
            i,
            new WhatsAppDeliveryLog(
                log.id(),
                log.toPhone(),
                log.templateName(),
                log.components(),
                log.waMessageId(),
                WhatsAppLogStatus.FAILED,
                log.costRs(),
                log.sentAt(),
                log.deliveredAt(),
                log.readAt(),
                errorCode,
                errorMessage));
        return true;
      }
      return false;
    }

    @Override
    public Page list(ListFilter filter) {
      List<WhatsAppDeliveryLog> filtered =
          rows.stream()
              .filter(l -> filter.toPhone() == null || filter.toPhone().equals(l.toPhone()))
              .filter(
                  l ->
                      filter.templateName() == null
                          || filter.templateName().equals(l.templateName()))
              .filter(l -> filter.status() == null || filter.status() == l.status())
              .filter(l -> filter.dateFrom() == null || !l.sentAt().isBefore(filter.dateFrom()))
              .filter(l -> filter.dateTo() == null || !l.sentAt().isAfter(filter.dateTo()))
              .collect(Collectors.toCollection(ArrayList::new));
      int from = Math.min((filter.page() - 1) * filter.limit(), filtered.size());
      int to = Math.min(from + filter.limit(), filtered.size());
      return new Page(filtered.subList(from, to), filtered.size());
    }
  }

  static final class FakeWhatsAppOptoutStore implements WhatsAppOptoutStore {
    private final ConcurrentHashMap<String, WhatsAppOptout> active = new ConcurrentHashMap<>();

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

  static final class FakeWhatsAppSessionStore implements WhatsAppSessionStore {
    private final ConcurrentHashMap<String, Instant> byPhone = new ConcurrentHashMap<>();

    @Override
    public void upsertCustomerMessage(String phone, Instant at) {
      byPhone.put(phone, at);
    }

    @Override
    public Optional<Instant> lastCustomerMessageAt(String phone) {
      return Optional.ofNullable(byPhone.get(phone));
    }
  }
}
