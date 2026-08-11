package com.nammamedmate.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.notification.adapter.out.client.StubAttachmentFetcher;
import com.nammamedmate.notification.adapter.out.client.StubSendGridClient;
import com.nammamedmate.notification.adapter.out.client.StubSesClient;
import com.nammamedmate.notification.application.port.out.EmailBounceStore;
import com.nammamedmate.notification.application.port.out.EmailDeliveryLogStore;
import com.nammamedmate.notification.application.port.out.EmailTemplateStore;
import com.nammamedmate.notification.application.port.out.EmailUnsubscribeStore;
import com.nammamedmate.notification.domain.EmailBounce;
import com.nammamedmate.notification.domain.EmailBounceType;
import com.nammamedmate.notification.domain.EmailCategory;
import com.nammamedmate.notification.domain.EmailDeliveryLog;
import com.nammamedmate.notification.domain.EmailLogStatus;
import com.nammamedmate.notification.domain.EmailProvider;
import com.nammamedmate.notification.domain.EmailTemplate;
import com.nammamedmate.notification.domain.EmailUnsubscribe;
import com.nammamedmate.notification.domain.EmailUnsubscribeSource;
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

class EmailServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T08:25:00Z");
  private static final UUID ADMIN = UUID.fromString("a0000001-0000-4000-8000-000000000001");
  private static final String EMAIL = "ravi.kumar@example.com";

  private FakeEmailTemplateStore templates;
  private FakeEmailDeliveryLogStore logs;
  private FakeEmailBounceStore bounces;
  private FakeEmailUnsubscribeStore unsubscribes;
  private StubSendGridClient sendGrid;
  private StubSesClient ses;
  private StubAttachmentFetcher attachments;
  private UnsubscribeTokenService tokens;
  private EmailSendService send;
  private EmailAdminService admin;
  private EmailUnsubscribeService unsubscribeService;
  private Clock clock;

  @BeforeEach
  void setUp() {
    clock = Clock.fixed(NOW, ZoneOffset.UTC);
    templates = new FakeEmailTemplateStore();
    logs = new FakeEmailDeliveryLogStore();
    bounces = new FakeEmailBounceStore();
    unsubscribes = new FakeEmailUnsubscribeStore();
    sendGrid = new StubSendGridClient();
    ses = new StubSesClient();
    attachments = new StubAttachmentFetcher();
    tokens = new UnsubscribeTokenService("test-email-unsubscribe-secret-key!!", clock);
    seedTemplates();
    send =
        new EmailSendService(
            templates,
            logs,
            bounces,
            unsubscribes,
            sendGrid,
            ses,
            attachments,
            channel -> Optional.of("SENDGRID"),
            AllowAllPreferenceGate.INSTANCE,
            tokens,
            clock,
            "http://localhost:8080");
    admin = new EmailAdminService(templates, logs, clock);
    unsubscribeService =
        new EmailUnsubscribeService(
            unsubscribes, tokens, PreferenceTestFakes.preferenceService(clock), clock);
  }

  @Test
  void ac001_hardBounceRejectedWithoutSendGrid() {
    bounces.insert(
        new EmailBounce(
            Ids.newId(), EMAIL, EmailBounceType.HARD, "mailbox full forever", true, NOW));
    assertThatThrownBy(
            () ->
                send.send(
                    new EmailSendService.SendCommand(
                        EMAIL,
                        "Ravi",
                        "ORDER_CONFIRMATION",
                        Map.of("order_id", "1"),
                        List.of(),
                        null)))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RECIPIENT_HARD_BOUNCED");
    assertThat(sendGrid.sendCallCount()).isZero();
  }

  @Test
  void ac002_transactionalBypassesUnsubscribe() {
    unsubscribes.upsertActive(Ids.newId(), EMAIL, EmailUnsubscribeSource.LINK_CLICK, NOW);
    Map<String, Object> result =
        send.send(
            new EmailSendService.SendCommand(
                EMAIL,
                "Ravi",
                "ORDER_CONFIRMATION",
                Map.of(
                    "customer_name",
                    "Ravi",
                    "order_id",
                    "ORD-1",
                    "total_amount",
                    "Rs 1",
                    "track_url",
                    "https://x"),
                List.of(),
                null));
    assertThat(result.get("status")).isEqualTo("SENT");
    assertThat(result.get("provider")).isEqualTo("SENDGRID");
    assertThat(sendGrid.sendCallCount()).isEqualTo(1);
  }

  @Test
  void ac003_sendGrid503FallsBackToSes() {
    sendGrid.setServerError(true);
    Map<String, Object> result =
        send.send(
            new EmailSendService.SendCommand(
                EMAIL,
                "Ravi",
                "ORDER_CONFIRMATION",
                Map.of(
                    "customer_name",
                    "Ravi",
                    "order_id",
                    "1",
                    "total_amount",
                    "Rs 1",
                    "track_url",
                    "https://x"),
                List.of(),
                null));
    assertThat(result.get("provider")).isEqualTo("SES");
    assertThat(result.get("fallback_used")).isEqualTo(true);
    assertThat(logs.all().get(0).fallbackUsed()).isTrue();
    assertThat(ses.sendCallCount()).isEqualTo(1);
  }

  @Test
  void ac004_unsubscribeLinkAddsToEmailUnsubscribes() {
    Map<String, Object> sent =
        send.send(
            new EmailSendService.SendCommand(
                EMAIL,
                "Ravi",
                "WEEKLY_OFFERS",
                Map.of(
                    "customer_name",
                    "Ravi",
                    "offer_title",
                    "Vitamins",
                    "shop_url",
                    "https://app.nammamedmate.in/shop"),
                List.of(),
                null));
    String url = (String) sent.get("unsubscribe_url");
    assertThat(url).contains("/api/v1/notifications/unsubscribe?token=");
    String token = url.substring(url.indexOf("token=") + 6);
    Map<String, Object> unsub = unsubscribeService.unsubscribe(token);
    assertThat(unsub.get("unsubscribed")).isEqualTo(true);
    assertThat(unsubscribes.isActivelyUnsubscribed(EMAIL)).isTrue();
  }

  @Test
  void ac005_templatesReturnOpenClickRates() {
    UUID log1 = Ids.newId();
    UUID log2 = Ids.newId();
    logs.insert(
        new EmailDeliveryLog(
            log1,
            EMAIL,
            "Ravi",
            "ORDER_CONFIRMATION",
            "subj",
            EmailProvider.SENDGRID,
            false,
            "sg1",
            EmailLogStatus.SENT,
            NOW,
            null,
            null,
            null,
            null,
            null));
    logs.insert(
        new EmailDeliveryLog(
            log2,
            EMAIL,
            "Ravi",
            "ORDER_CONFIRMATION",
            "subj",
            EmailProvider.SENDGRID,
            false,
            "sg2",
            EmailLogStatus.OPENED,
            NOW,
            NOW,
            NOW,
            null,
            null,
            null));
    logs.markClicked(log2, NOW);
    Map<String, Object> listed = admin.listTemplates(null, true);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) listed.get("templates");
    Map<String, Object> order =
        rows.stream()
            .filter(r -> "ORDER_CONFIRMATION".equals(r.get("id")))
            .findFirst()
            .orElseThrow();
    assertThat(order.get("open_rate_pct")).isEqualTo(50.0d);
    assertThat(order.get("click_rate_pct")).isEqualTo(50.0d);
  }

  @Test
  void ac006_spamWebhookUnsubscribes() {
    send.send(
        new EmailSendService.SendCommand(
            EMAIL,
            "Ravi",
            "ORDER_CONFIRMATION",
            Map.of(
                "customer_name",
                "Ravi",
                "order_id",
                "1",
                "total_amount",
                "Rs 1",
                "track_url",
                "https://x"),
            List.of(),
            null));
    String msgId = logs.all().get(0).providerMessageId();
    send.handleWebhook(
        List.of(Map.of("email", EMAIL, "event", "spamreport", "sg_message_id", msgId)));
    assertThat(unsubscribes.isActivelyUnsubscribed(EMAIL)).isTrue();
    assertThat(logs.all().get(0).status()).isEqualTo(EmailLogStatus.SPAM);
  }

  @Test
  void ac007_attachment404SendsWithoutAttachment() {
    String badUrl = "https://s3.example.com/missing.pdf";
    attachments.putNotFound(badUrl);
    Map<String, Object> result =
        send.send(
            new EmailSendService.SendCommand(
                EMAIL,
                "Ravi",
                "ORDER_CONFIRMATION",
                Map.of(
                    "customer_name",
                    "Ravi",
                    "order_id",
                    "1",
                    "total_amount",
                    "Rs 1",
                    "track_url",
                    "https://x"),
                List.of(new EmailSendService.AttachmentRef("invoice.pdf", badUrl)),
                null));
    assertThat(result.get("status")).isEqualTo("SENT");
    assertThat(sendGrid.sendCallCount()).isEqualTo(1);
  }

  @Test
  void ac008_undefinedHandlebarsRendersEmpty() {
    Map<String, Object> result =
        send.send(
            new EmailSendService.SendCommand(
                EMAIL,
                "Ravi",
                "ORDER_CONFIRMATION",
                Map.of("customer_name", "Ravi", "order_id", "ORD-9", "track_url", "https://x"),
                List.of(),
                null));
    assertThat(result.get("status")).isEqualTo("SENT");
    assertThat(logs.all().get(0).subject()).doesNotContain("{{");
    assertThat(logs.all().get(0).subject()).contains("ORD-9");
  }

  private void seedTemplates() {
    templates.upsert(
        new EmailTemplate(
            "ORDER_CONFIRMATION",
            "Order Confirmation",
            "Your Namma MedMate order #{{order_id}} is confirmed!",
            "<p>Hi {{customer_name}}, order {{order_id}} for {{total_amount}}. <a href=\"{{track_url}}\">Track</a></p><p>You are receiving this because it relates to your account activity.</p>",
            "Hi {{customer_name}}, order {{order_id}} for {{total_amount}}. {{undefined_variable}}",
            EmailCategory.TRANSACTIONAL,
            true,
            1,
            ADMIN,
            NOW,
            NOW));
    templates.upsert(
        new EmailTemplate(
            "WEEKLY_OFFERS",
            "Weekly Offers",
            "Offers for {{customer_name}}",
            "<p>Hi {{customer_name}}, {{offer_title}}! <a href=\"{{shop_url}}\">Shop</a></p>{{#if show_code}}<p>{{promo_code}}</p>{{/if}}",
            "Hi {{customer_name}}, {{offer_title}}",
            EmailCategory.MARKETING,
            true,
            1,
            ADMIN,
            NOW,
            NOW));
  }

  static final class FakeEmailTemplateStore implements EmailTemplateStore {
    private final Map<String, EmailTemplate> byId = new ConcurrentHashMap<>();

    @Override
    public Optional<EmailTemplate> findById(String templateId) {
      return Optional.ofNullable(byId.get(templateId));
    }

    @Override
    public boolean exists(String templateId) {
      return byId.containsKey(templateId);
    }

    @Override
    public void upsert(EmailTemplate template) {
      byId.put(template.templateId(), template);
    }

    @Override
    public List<EmailTemplate> list(EmailCategory category, Boolean active) {
      return byId.values().stream()
          .filter(t -> category == null || t.category() == category)
          .filter(t -> active == null || t.active() == active)
          .collect(Collectors.toList());
    }
  }

  static final class FakeEmailDeliveryLogStore implements EmailDeliveryLogStore {
    private final Map<UUID, EmailDeliveryLog> byId = new ConcurrentHashMap<>();

    List<EmailDeliveryLog> all() {
      return new ArrayList<>(byId.values());
    }

    @Override
    public void insert(EmailDeliveryLog log) {
      byId.put(log.id(), log);
    }

    @Override
    public Optional<EmailDeliveryLog> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<EmailDeliveryLog> findByProviderMessageId(String providerMessageId) {
      return byId.values().stream()
          .filter(l -> providerMessageId.equals(l.providerMessageId()))
          .findFirst();
    }

    @Override
    public boolean markDelivered(String providerMessageId, Instant at) {
      return findByProviderMessageId(providerMessageId)
          .map(
              l -> {
                byId.put(
                    l.id(),
                    new EmailDeliveryLog(
                        l.id(),
                        l.toEmail(),
                        l.toName(),
                        l.templateId(),
                        l.subject(),
                        l.provider(),
                        l.fallbackUsed(),
                        l.providerMessageId(),
                        EmailLogStatus.DELIVERED,
                        l.sentAt(),
                        at,
                        l.openedAt(),
                        l.clickedAt(),
                        l.bounceType(),
                        l.errorMessage()));
                return true;
              })
          .orElse(false);
    }

    @Override
    public boolean markOpened(UUID logId, Instant at) {
      EmailDeliveryLog l = byId.get(logId);
      if (l == null) {
        return false;
      }
      byId.put(
          logId,
          new EmailDeliveryLog(
              l.id(),
              l.toEmail(),
              l.toName(),
              l.templateId(),
              l.subject(),
              l.provider(),
              l.fallbackUsed(),
              l.providerMessageId(),
              l.status() == EmailLogStatus.CLICKED ? EmailLogStatus.CLICKED : EmailLogStatus.OPENED,
              l.sentAt(),
              l.deliveredAt(),
              l.openedAt() == null ? at : l.openedAt(),
              l.clickedAt(),
              l.bounceType(),
              l.errorMessage()));
      return true;
    }

    @Override
    public boolean markClicked(UUID logId, Instant at) {
      EmailDeliveryLog l = byId.get(logId);
      if (l == null) {
        return false;
      }
      byId.put(
          logId,
          new EmailDeliveryLog(
              l.id(),
              l.toEmail(),
              l.toName(),
              l.templateId(),
              l.subject(),
              l.provider(),
              l.fallbackUsed(),
              l.providerMessageId(),
              EmailLogStatus.CLICKED,
              l.sentAt(),
              l.deliveredAt(),
              l.openedAt() == null ? at : l.openedAt(),
              at,
              l.bounceType(),
              l.errorMessage()));
      return true;
    }

    @Override
    public boolean markBounced(String providerMessageId, EmailBounceType bounceType, Instant at) {
      return findByProviderMessageId(providerMessageId)
          .map(
              l -> {
                byId.put(
                    l.id(),
                    new EmailDeliveryLog(
                        l.id(),
                        l.toEmail(),
                        l.toName(),
                        l.templateId(),
                        l.subject(),
                        l.provider(),
                        l.fallbackUsed(),
                        l.providerMessageId(),
                        EmailLogStatus.BOUNCED,
                        l.sentAt(),
                        l.deliveredAt(),
                        l.openedAt(),
                        l.clickedAt(),
                        bounceType,
                        l.errorMessage()));
                return true;
              })
          .orElse(false);
    }

    @Override
    public boolean markSpam(String providerMessageId, Instant at) {
      return findByProviderMessageId(providerMessageId)
          .map(
              l -> {
                byId.put(
                    l.id(),
                    new EmailDeliveryLog(
                        l.id(),
                        l.toEmail(),
                        l.toName(),
                        l.templateId(),
                        l.subject(),
                        l.provider(),
                        l.fallbackUsed(),
                        l.providerMessageId(),
                        EmailLogStatus.SPAM,
                        l.sentAt(),
                        l.deliveredAt(),
                        l.openedAt(),
                        l.clickedAt(),
                        l.bounceType(),
                        l.errorMessage()));
                return true;
              })
          .orElse(false);
    }

    @Override
    public Page list(ListFilter filter) {
      List<EmailDeliveryLog> filtered =
          byId.values().stream()
              .filter(l -> filter.toEmail() == null || filter.toEmail().equals(l.toEmail()))
              .filter(
                  l -> filter.templateId() == null || filter.templateId().equals(l.templateId()))
              .filter(l -> filter.status() == null || filter.status() == l.status())
              .collect(Collectors.toList());
      return new Page(filtered, filtered.size());
    }

    @Override
    public TemplateStats statsForTemplate(String templateId) {
      List<EmailDeliveryLog> rows =
          byId.values().stream()
              .filter(l -> templateId.equals(l.templateId()))
              .filter(
                  l ->
                      l.status() == EmailLogStatus.SENT
                          || l.status() == EmailLogStatus.DELIVERED
                          || l.status() == EmailLogStatus.OPENED
                          || l.status() == EmailLogStatus.CLICKED)
              .toList();
      Instant last =
          rows.stream()
              .map(EmailDeliveryLog::sentAt)
              .filter(java.util.Objects::nonNull)
              .max(Instant::compareTo)
              .orElse(null);
      long opened = rows.stream().filter(l -> l.openedAt() != null).count();
      long clicked = rows.stream().filter(l -> l.clickedAt() != null).count();
      return new TemplateStats(last, rows.size(), opened, clicked);
    }
  }

  static final class FakeEmailBounceStore implements EmailBounceStore {
    private final List<EmailBounce> rows = new ArrayList<>();

    @Override
    public void insert(EmailBounce bounce) {
      rows.add(bounce);
    }

    @Override
    public boolean hasHardBounce(String email) {
      return rows.stream()
          .anyMatch(b -> email.equals(b.email()) && b.bounceType() == EmailBounceType.HARD);
    }

    @Override
    public Optional<EmailBounce> findLatestHard(String email) {
      return rows.stream()
          .filter(b -> email.equals(b.email()) && b.bounceType() == EmailBounceType.HARD)
          .reduce((a, b) -> b);
    }
  }

  static final class FakeEmailUnsubscribeStore implements EmailUnsubscribeStore {
    private final Map<String, EmailUnsubscribe> active = new ConcurrentHashMap<>();

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
