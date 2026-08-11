package com.nammamedmate.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.notification.adapter.out.client.StubAttachmentFetcher;
import com.nammamedmate.notification.adapter.out.client.StubSendGridClient;
import com.nammamedmate.notification.adapter.out.client.StubSesClient;
import com.nammamedmate.notification.application.port.out.EmailDeliveryLogStore;
import com.nammamedmate.notification.application.port.out.SesClientPort;
import com.nammamedmate.notification.domain.EmailCategory;
import com.nammamedmate.notification.domain.EmailDeliveryLog;
import com.nammamedmate.notification.domain.EmailLogStatus;
import com.nammamedmate.notification.domain.EmailProvider;
import com.nammamedmate.notification.domain.EmailTemplate;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmailCoverageFillTest {

  private static final Instant NOW = Instant.parse("2026-07-24T08:25:00Z");
  private static final UUID ADMIN = UUID.fromString("a0000001-0000-4000-8000-000000000001");

  @Test
  void fillsRemainingBranches() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    var templates = new EmailServiceAcTest.FakeEmailTemplateStore();
    var logs = new EmailServiceAcTest.FakeEmailDeliveryLogStore();
    var bounces = new EmailServiceAcTest.FakeEmailBounceStore();
    var unsubs = new EmailServiceAcTest.FakeEmailUnsubscribeStore();
    StubSendGridClient sendGrid = new StubSendGridClient();
    StubSesClient ses = new StubSesClient();
    StubAttachmentFetcher attachments = new StubAttachmentFetcher();
    UnsubscribeTokenService tokens = new UnsubscribeTokenService("short-secret", clock);
    templates.upsert(
        new EmailTemplate(
            "ORDER_CONFIRMATION",
            "Order",
            "Order {{order_id}}",
            "<p>{{customer_name}}</p>",
            null,
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
            "Hi",
            "<p>x</p>",
            "t",
            EmailCategory.MARKETING,
            true,
            1,
            null,
            NOW,
            NOW));

    EmailSendService sendBlankBase =
        new EmailSendService(
            templates,
            logs,
            bounces,
            unsubs,
            sendGrid,
            ses,
            attachments,
            channel -> Optional.of("SENDGRID"),
            AllowAllPreferenceGate.INSTANCE,
            tokens,
            clock,
            "   ");
    EmailSendService send =
        new EmailSendService(
            templates,
            logs,
            bounces,
            unsubs,
            sendGrid,
            ses,
            attachments,
            channel -> Optional.of("SENDGRID"),
            AllowAllPreferenceGate.INSTANCE,
            tokens,
            clock,
            "http://localhost:8080");
    EmailAdminService admin = new EmailAdminService(templates, logs, clock);

    assertThatThrownBy(
            () ->
                send.send(
                    new EmailSendService.SendCommand(
                        "a@b.com", null, null, Map.of("order_id", "1"), null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("TEMPLATE_NOT_FOUND");

    assertThatThrownBy(
            () ->
                send.send(
                    new EmailSendService.SendCommand(
                        null, null, "ORDER_CONFIRMATION", Map.of(), List.of(), null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_EMAIL");

    Map<String, Object> withNullUrlAtt =
        send.send(
            new EmailSendService.SendCommand(
                "c@b.com",
                "C",
                "ORDER_CONFIRMATION",
                Map.of("order_id", "1", "customer_name", "C"),
                List.of(new EmailSendService.AttachmentRef("f.pdf", null)),
                null));
    assertThat(withNullUrlAtt.get("status")).isEqualTo("SENT");

    assertThatThrownBy(() -> admin.upsertTemplate(ADMIN, null, "n", "s", "h", "t", "TRANSACTIONAL"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_TEMPLATE_ID");

    logs.insert(
        new EmailDeliveryLog(
            Ids.newId(),
            "nullsent@z.com",
            null,
            "ORDER_CONFIRMATION",
            "s",
            EmailProvider.SENDGRID,
            false,
            null,
            EmailLogStatus.SENT,
            null,
            null,
            null,
            null,
            null,
            null));
    assertThat(admin.listLogs("nullsent@z.com", null, null, null, null, 0, null).total())
        .isGreaterThan(0);

    String okUrl = "https://s3/ok.pdf";
    attachments.putOk(okUrl, "x".getBytes(StandardCharsets.UTF_8), null);
    sendGrid.setServerError(true);
    Map<String, Object> fallback =
        sendBlankBase.send(
            new EmailSendService.SendCommand(
                "a@b.com",
                null,
                "ORDER_CONFIRMATION",
                Map.of("order_id", "1", "customer_name", "A"),
                List.of(new EmailSendService.AttachmentRef(" ", okUrl)),
                null));
    assertThat(fallback.get("fallback_used")).isEqualTo(true);
    sendGrid.reset();
    ses.reset();

    sendGrid.setServerError(true);
    SesClientPort nullMsgSes = request -> SesClientPort.SendResult.fail(null);
    EmailSendService bothFail =
        new EmailSendService(
            templates,
            logs,
            bounces,
            unsubs,
            sendGrid,
            nullMsgSes,
            attachments,
            channel -> Optional.of("SENDGRID"),
            AllowAllPreferenceGate.INSTANCE,
            tokens,
            clock,
            "http://localhost:8080");
    assertThatThrownBy(
            () ->
                bothFail.send(
                    new EmailSendService.SendCommand(
                        "b@b.com",
                        null,
                        "ORDER_CONFIRMATION",
                        Map.of("order_id", "1"),
                        List.of(),
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ALL_PROVIDERS_FAILED");
    sendGrid.reset();

    send.handleWebhook(List.of(Map.of("event", "processed")));
    send.handleWebhook(List.of(Map.of("event", "delivered")));
    send.handleWebhook(List.of(Map.of("type", "spamreport")));
    send.handleWebhook(List.of(Map.of("event", "bounce")));
    send.handleWebhook(List.of(Map.of("event", "open", "log_id", "not-uuid")));
    send.handleWebhook(List.of(Map.of("event", "open")));
    send.handleWebhook(List.of(Map.of("event", "click", "custom_args", Map.of("log_id", "bad"))));
    send.handleWebhook(
        List.of(Map.of("email", "soft@b.com", "event", "bounce", "type", "soft", "smtp-id", "s1")));

    EmailAdminService.LogPage page = admin.listLogs(null, null, "  ", null, null, null, 200);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) page.data().get("logs");
    assertThat(rows).isNotEmpty();

    UUID id = Ids.newId();
    logs.insert(
        new EmailDeliveryLog(
            id,
            "z@z.com",
            null,
            "ORDER_CONFIRMATION",
            "s",
            null,
            false,
            null,
            EmailLogStatus.SENT,
            NOW,
            NOW,
            NOW,
            NOW,
            null,
            null));
    assertThat(admin.listLogs("z@z.com", null, "SENT", null, null, 1, 20).total()).isEqualTo(1);

    assertThat(new EmailAdminService.LogPage(null, 1, 20, 0).data()).isEmpty();
    assertThat(new EmailDeliveryLogStore.Page(null, 0).logs()).isEmpty();

    byte[] bytes = "short-secret".getBytes(StandardCharsets.UTF_8);
    byte[] padded = new byte[32];
    System.arraycopy(bytes, 0, padded, 0, bytes.length);
    var key = Keys.hmacShaKeyFor(padded);

    String badPurpose =
        Jwts.builder()
            .subject("x")
            .claim("email", "a@b.com")
            .claim("purpose", "other")
            .expiration(Date.from(NOW.plusSeconds(3600)))
            .signWith(key)
            .compact();
    assertThatThrownBy(() -> tokens.parse(badPurpose))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_TOKEN");

    String noEmail =
        Jwts.builder()
            .subject("x")
            .claim("purpose", UnsubscribeTokenService.PURPOSE)
            .expiration(Date.from(NOW.plusSeconds(3600)))
            .signWith(key)
            .compact();
    assertThatThrownBy(() -> tokens.parse(noEmail))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_TOKEN");

    // force blank email after null check
    String blankEmailClaim =
        Jwts.builder()
            .subject("x")
            .claim("email", "\t  \n")
            .claim("purpose", UnsubscribeTokenService.PURPOSE)
            .expiration(Date.from(NOW.plusSeconds(3600)))
            .signWith(key)
            .compact();
    assertThatThrownBy(() -> tokens.parse(blankEmailClaim))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_TOKEN");

    // blankToNull empty-after-trim via whitespace toName
    assertThat(
            send.send(
                    new EmailSendService.SendCommand(
                        "ws@b.com",
                        "   ",
                        "ORDER_CONFIRMATION",
                        Map.of("order_id", "1", "customer_name", "W"),
                        List.of(),
                        null))
                .get("status"))
        .isEqualTo("SENT");

    assertThat(HandlebarsRenderer.htmlToPlainText("")).isEmpty();
    assertThat(HandlebarsRenderer.render("{{#if nest.x}}Y{{/if}}", Map.of("nest", "notmap")))
        .isEmpty();
    assertThat(HandlebarsRenderer.resolve(Map.of("a", 1), " ")).isNull();

    StubAttachmentFetcher fetcher = new StubAttachmentFetcher();
    assertThat(fetcher.fetch(null).found()).isTrue();

    Map<String, Object> created =
        admin.upsertTemplate(ADMIN, "BRAND_NEW", null, null, null, "  ", "LIFECYCLE");
    assertThat(created.get("template_id")).isEqualTo("BRAND_NEW");

    Map<String, Object> up =
        admin.upsertTemplate(ADMIN, "WEEKLY_OFFERS", "Offers2", "s", "<p>h</p>", "t", "MARKETING");
    assertThat(up.get("version")).isEqualTo(2);

    // existing with non-null createdBy keeps it
    templates.upsert(
        new EmailTemplate(
            "KEEP_BY",
            "Keep",
            "s",
            "<p>x</p>",
            "t",
            EmailCategory.LIFECYCLE,
            true,
            1,
            ADMIN,
            NOW,
            NOW));
    Map<String, Object> kept =
        admin.upsertTemplate(
            UUID.randomUUID(), "KEEP_BY", "Keep2", "s2", "<p>y</p>", "t2", "LIFECYCLE");
    assertThat(kept.get("version")).isEqualTo(2);

    String noSubject =
        Jwts.builder()
            .claim("email", "nosub@b.com")
            .claim("purpose", UnsubscribeTokenService.PURPOSE)
            .expiration(Date.from(NOW.plusSeconds(3600)))
            .signWith(key)
            .compact();
    assertThat(tokens.parse(noSubject).customerId()).isNull();

    EmailSendService trailing =
        new EmailSendService(
            templates,
            logs,
            bounces,
            unsubs,
            sendGrid,
            ses,
            attachments,
            channel -> Optional.of("SENDGRID"),
            AllowAllPreferenceGate.INSTANCE,
            tokens,
            clock,
            "http://localhost:8080/");
    assertThat(
            trailing
                .send(
                    new EmailSendService.SendCommand(
                        "trail@b.com",
                        null,
                        "ORDER_CONFIRMATION",
                        Map.of("order_id", "1", "customer_name", "T"),
                        List.of(),
                        null))
                .get("status"))
        .isEqualTo("SENT");

    attachments.putOk(
        "https://s3/named.pdf", "x".getBytes(StandardCharsets.UTF_8), "application/pdf");
    assertThat(
            send.send(
                    new EmailSendService.SendCommand(
                        "named@b.com",
                        null,
                        "ORDER_CONFIRMATION",
                        Map.of("order_id", "1", "customer_name", "N"),
                        List.of(
                            new EmailSendService.AttachmentRef(
                                "invoice.pdf", "https://s3/named.pdf")),
                        null))
                .get("status"))
        .isEqualTo("SENT");

    // explicit null base-url branch
    new EmailSendService(
        templates,
        logs,
        bounces,
        unsubs,
        sendGrid,
        ses,
        attachments,
        channel -> Optional.of("SENDGRID"),
        AllowAllPreferenceGate.INSTANCE,
        tokens,
        clock,
        null);

    assertThatThrownBy(() -> tokens.parse("   "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_TOKEN");
    assertThatThrownBy(() -> tokens.parse(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_TOKEN");

    // blankToNull via admin list filter whitespace-only
    assertThat(admin.listLogs("   ", "   ", null, null, null, 1, 20).data()).isNotNull();

    // firstNonBlank all blank/null via bounce without ids
    send.handleWebhook(
        List.of(
            Map.of(
                "event",
                "delivered",
                "sg_message_id",
                "  ",
                "provider_message_id",
                "",
                "smtp-id",
                " ")));

    assertThat(admin.listTemplates("  ", true).get("templates")).isInstanceOf(List.class);
    assertThat(admin.listTemplates(null, null).get("templates")).isInstanceOf(List.class);
  }
}
