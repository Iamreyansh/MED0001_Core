package com.nammamedmate.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.notification.adapter.out.client.StubAttachmentFetcher;
import com.nammamedmate.notification.adapter.out.client.StubSendGridClient;
import com.nammamedmate.notification.adapter.out.client.StubSesClient;
import com.nammamedmate.notification.domain.EmailBounce;
import com.nammamedmate.notification.domain.EmailBounceType;
import com.nammamedmate.notification.domain.EmailCategory;
import com.nammamedmate.notification.domain.EmailTemplate;
import com.nammamedmate.notification.domain.EmailUnsubscribeSource;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmailSendCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T08:25:00Z");
  private static final UUID ADMIN = UUID.fromString("a0000001-0000-4000-8000-000000000001");

  private EmailServiceAcTest.FakeEmailTemplateStore templates;
  private EmailServiceAcTest.FakeEmailDeliveryLogStore logs;
  private EmailServiceAcTest.FakeEmailBounceStore bounces;
  private EmailServiceAcTest.FakeEmailUnsubscribeStore unsubscribes;
  private StubSendGridClient sendGrid;
  private StubSesClient ses;
  private StubAttachmentFetcher attachments;
  private UnsubscribeTokenService tokens;
  private EmailSendService send;
  private EmailAdminService admin;
  private EmailUnsubscribeService unsubscribeService;

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    templates = new EmailServiceAcTest.FakeEmailTemplateStore();
    logs = new EmailServiceAcTest.FakeEmailDeliveryLogStore();
    bounces = new EmailServiceAcTest.FakeEmailBounceStore();
    unsubscribes = new EmailServiceAcTest.FakeEmailUnsubscribeStore();
    sendGrid = new StubSendGridClient();
    ses = new StubSesClient();
    attachments = new StubAttachmentFetcher();
    tokens = new UnsubscribeTokenService("test-email-unsubscribe-secret-key!!", clock);
    templates.upsert(
        new EmailTemplate(
            "ORDER_CONFIRMATION",
            "Order",
            "Order {{order_id}}",
            "<p>{{customer_name}}</p>",
            "",
            EmailCategory.TRANSACTIONAL,
            true,
            1,
            ADMIN,
            NOW,
            NOW));
    templates.upsert(
        new EmailTemplate(
            "WEEKLY_OFFERS",
            "Offers",
            "Hi {{customer_name}}",
            "<p><a href=\"https://shop.example\">Shop</a></p>",
            "text",
            EmailCategory.MARKETING,
            true,
            1,
            ADMIN,
            NOW,
            NOW));
    templates.upsert(
        new EmailTemplate(
            "INACTIVE",
            "Inactive",
            "x",
            "<p>x</p>",
            "x",
            EmailCategory.LIFECYCLE,
            false,
            1,
            ADMIN,
            NOW,
            NOW));
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
            "http://localhost:8080/");
    admin = new EmailAdminService(templates, logs, clock);
    unsubscribeService =
        new EmailUnsubscribeService(
            unsubscribes, tokens, PreferenceTestFakes.preferenceService(clock), clock);
  }

  @Test
  void rejectsInvalidEmailTemplateMissingAndInactive() {
    assertThatThrownBy(
            () ->
                send.send(
                    new EmailSendService.SendCommand(
                        "bad", null, "ORDER_CONFIRMATION", Map.of(), List.of(), null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_EMAIL");
    assertThatThrownBy(
            () ->
                send.send(
                    new EmailSendService.SendCommand(
                        "a@b.com", null, "NOPE", Map.of(), List.of(), null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("TEMPLATE_NOT_FOUND");
    assertThatThrownBy(
            () ->
                send.send(
                    new EmailSendService.SendCommand(
                        "a@b.com", null, "INACTIVE", Map.of(), List.of(), null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("TEMPLATE_INACTIVE");
  }

  @Test
  void marketingBlockedWhenUnsubscribed() {
    unsubscribes.upsertActive(Ids.newId(), "a@b.com", EmailUnsubscribeSource.LINK_CLICK, NOW);
    assertThatThrownBy(
            () ->
                send.send(
                    new EmailSendService.SendCommand(
                        "a@b.com",
                        null,
                        "WEEKLY_OFFERS",
                        Map.of("customer_name", "A"),
                        List.of(),
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RECIPIENT_UNSUBSCRIBED");
  }

  @Test
  void channelDownAndBothProvidersFail() {
    EmailSendService noChannel =
        new EmailSendService(
            templates,
            logs,
            bounces,
            unsubscribes,
            sendGrid,
            ses,
            attachments,
            channel -> Optional.empty(),
            AllowAllPreferenceGate.INSTANCE,
            tokens,
            Clock.fixed(NOW, ZoneOffset.UTC),
            "http://localhost:8080");
    assertThatThrownBy(
            () ->
                noChannel.send(
                    new EmailSendService.SendCommand(
                        "a@b.com",
                        null,
                        "ORDER_CONFIRMATION",
                        Map.of("order_id", "1"),
                        List.of(),
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ALL_PROVIDERS_FAILED");

    sendGrid.setServerError(true);
    ses.setFail(true);
    assertThatThrownBy(
            () ->
                send.send(
                    new EmailSendService.SendCommand(
                        "a@b.com",
                        null,
                        "ORDER_CONFIRMATION",
                        Map.of("order_id", "1"),
                        List.of(),
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ALL_PROVIDERS_FAILED");

    sendGrid.reset();
    ses.reset();
    sendGrid.setFail(true);
    assertThatThrownBy(
            () ->
                send.send(
                    new EmailSendService.SendCommand(
                        "a@b.com",
                        null,
                        "ORDER_CONFIRMATION",
                        Map.of("order_id", "1"),
                        List.of(),
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ALL_PROVIDERS_FAILED");
  }

  @Test
  void attachmentTooLargeAndWebhookBranches() {
    String url = "https://s3/big.pdf";
    byte[] big = new byte[(int) (EmailSendService.MAX_ATTACHMENT_BYTES) + 1];
    attachments.putOk(url, big, "application/pdf");
    assertThatThrownBy(
            () ->
                send.send(
                    new EmailSendService.SendCommand(
                        "a@b.com",
                        "A",
                        "ORDER_CONFIRMATION",
                        Map.of("order_id", "1"),
                        List.of(new EmailSendService.AttachmentRef("big.pdf", url)),
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ATTACHMENT_TOO_LARGE");

    assertThat(send.handleWebhook(null).get("processed")).isEqualTo(0);
    assertThat(send.handleWebhook(List.of()).get("processed")).isEqualTo(0);
    java.util.ArrayList<Map<String, Object>> withNull = new java.util.ArrayList<>();
    withNull.add(null);
    assertThat(send.handleWebhook(withNull).get("processed")).isEqualTo(0);
    assertThat(send.handleWebhook(List.of(Map.of("foo", "bar"))).get("processed")).isEqualTo(0);

    Map<String, Object> sent =
        send.send(
            new EmailSendService.SendCommand(
                "a@b.com", null, "ORDER_CONFIRMATION", Map.of("order_id", "1"), List.of(), null));
    String msgId = (String) sent.get("provider_message_id");
    UUID logId = UUID.fromString((String) sent.get("log_id"));

    send.handleWebhook(List.of(Map.of("event", "delivered", "sg_message_id", msgId)));
    send.handleWebhook(
        List.of(
            Map.of(
                "email",
                "hard@b.com",
                "event",
                "bounce",
                "type",
                "bounce",
                "sg_message_id",
                msgId,
                "reason",
                "no mailbox")));
    assertThat(bounces.hasHardBounce("hard@b.com")).isTrue();

    send.handleWebhook(
        List.of(
            Map.of(
                "email",
                "soft@b.com",
                "event",
                "bounce",
                "bounce_type",
                "soft",
                "provider_message_id",
                "x")));
    send.handleWebhook(List.of(Map.of("event", "open", "log_id", logId.toString())));
    send.handleWebhook(
        List.of(Map.of("event", "click", "custom_args", Map.of("log_id", logId.toString()))));
    send.handleWebhook(List.of(Map.of("event", "spam", "email", "spam@b.com")));

    assertThat(send.trackOpen(logId).get("opened")).isEqualTo(true);
    assertThat(send.trackClick(logId, "https://x").get("redirect_url")).isEqualTo("https://x");
  }

  @Test
  void rewriteLinksSkipsInternalAndAdminPaths() {
    UUID id = Ids.newId();
    String html =
        "<a href=\"https://ext.example\">x</a><a href=\"http://localhost:8080/api/v1/notifications/unsubscribe?token=t\">u</a>";
    String out = send.rewriteLinks(html, id);
    assertThat(out).contains("/api/v1/notifications/email/t/c/" + id);
    assertThat(out).contains("unsubscribe?token=t");
    assertThat(send.rewriteLinks(null, id)).isEmpty();
    assertThat(send.rewriteLinks(" ", id)).isEqualTo(" ");
  }

  @Test
  void adminUpsertListAndInvalidFilters() {
    Map<String, Object> created =
        admin.upsertTemplate(
            ADMIN, "NEW_TPL", "New", "Hi {{name}}", "<p>{{name}}</p>", null, "LIFECYCLE");
    assertThat(created.get("template_id")).isEqualTo("NEW_TPL");
    Map<String, Object> updated =
        admin.upsertTemplate(ADMIN, "NEW_TPL", "New2", "Hi", "<p>x</p>", "plain", "LIFECYCLE");
    assertThat(updated.get("version")).isEqualTo(2);

    assertThatThrownBy(() -> admin.upsertTemplate(ADMIN, " ", "n", "s", "h", "t", "X"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_TEMPLATE_ID");
    assertThatThrownBy(() -> admin.upsertTemplate(ADMIN, "X", "n", "s", "h", "t", "NOPE"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_CATEGORY");
    assertThatThrownBy(() -> admin.listTemplates("NOPE", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_CATEGORY");
    assertThatThrownBy(() -> admin.listLogs(null, null, "NOPE", null, null, 0, 0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_STATUS");

    EmailAdminService.LogPage page = admin.listLogs("a@b.com", null, "SENT", null, null, 1, 20);
    assertThat(page.data().get("logs")).isInstanceOf(List.class);
    assertThat(EmailAdminService.ratePct(1, 0)).isEqualTo(0.0d);
    assertThat(admin.listTemplates("TRANSACTIONAL", true).get("templates"))
        .isInstanceOf(List.class);
  }

  @Test
  void unsubscribeTokenErrorsAndAlreadyUnsubscribed() {
    assertThatThrownBy(() -> unsubscribeService.unsubscribe(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_TOKEN");
    assertThatThrownBy(() -> unsubscribeService.unsubscribe("not-a-jwt"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_TOKEN");

    String token = tokens.issue("dup@b.com", UUID.randomUUID());
    unsubscribeService.unsubscribe(token);
    assertThatThrownBy(() -> unsubscribeService.unsubscribe(token))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ALREADY_UNSUBSCRIBED");

    Clock past = Clock.fixed(NOW.minusSeconds(8L * 24 * 3600), ZoneOffset.UTC);
    UnsubscribeTokenService oldTokens =
        new UnsubscribeTokenService("test-email-unsubscribe-secret-key!!", past);
    String expired = oldTokens.issue("old@b.com", null);
    assertThatThrownBy(() -> tokens.parse(expired))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("TOKEN_EXPIRED");
  }

  @Test
  void normalizeEmailAndAttachmentOkPath() {
    assertThat(EmailSendService.normalizeEmail("  A@B.COM ")).isEqualTo("a@b.com");
    String url = "https://s3/ok.pdf";
    attachments.putOk(url, "pdf".getBytes(StandardCharsets.UTF_8), "application/pdf");
    Map<String, Object> result =
        send.send(
            new EmailSendService.SendCommand(
                "a@b.com",
                "A",
                "ORDER_CONFIRMATION",
                Map.of("order_id", "1", "customer_name", "A"),
                List.of(
                    new EmailSendService.AttachmentRef(null, url),
                    new EmailSendService.AttachmentRef("x.pdf", " ")),
                UUID.randomUUID()));
    assertThat(result.get("status")).isEqualTo("SENT");

    bounces.insert(
        new EmailBounce(Ids.newId(), "x@y.com", EmailBounceType.SOFT, "temp", false, NOW));
    assertThat(bounces.hasHardBounce("x@y.com")).isFalse();
  }
}
