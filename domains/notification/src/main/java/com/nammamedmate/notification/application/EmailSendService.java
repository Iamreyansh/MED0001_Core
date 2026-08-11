package com.nammamedmate.notification.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.notification.application.port.out.AttachmentFetcherPort;
import com.nammamedmate.notification.application.port.out.CommunicationChannelLookupPort;
import com.nammamedmate.notification.application.port.out.EmailBounceStore;
import com.nammamedmate.notification.application.port.out.EmailDeliveryLogStore;
import com.nammamedmate.notification.application.port.out.EmailTemplateStore;
import com.nammamedmate.notification.application.port.out.EmailUnsubscribeStore;
import com.nammamedmate.notification.application.port.out.PreferenceGatePort;
import com.nammamedmate.notification.application.port.out.SendGridClientPort;
import com.nammamedmate.notification.application.port.out.SesClientPort;
import com.nammamedmate.notification.domain.EmailBounce;
import com.nammamedmate.notification.domain.EmailBounceType;
import com.nammamedmate.notification.domain.EmailDeliveryLog;
import com.nammamedmate.notification.domain.EmailLogStatus;
import com.nammamedmate.notification.domain.EmailProvider;
import com.nammamedmate.notification.domain.EmailTemplate;
import com.nammamedmate.notification.domain.EmailUnsubscribeSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class EmailSendService {

  private static final Logger log = LoggerFactory.getLogger(EmailSendService.class);
  static final long MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024;
  private static final String TX_NOTICE =
      "You are receiving this because it relates to your account activity.";

  private final EmailTemplateStore templates;
  private final EmailDeliveryLogStore logs;
  private final EmailBounceStore bounces;
  private final EmailUnsubscribeStore unsubscribes;
  private final SendGridClientPort sendGrid;
  private final SesClientPort ses;
  private final AttachmentFetcherPort attachments;
  private final CommunicationChannelLookupPort channels;
  private final PreferenceGatePort preferences;
  private final UnsubscribeTokenService tokens;
  private final Clock clock;
  private final String publicBaseUrl;

  public EmailSendService(
      EmailTemplateStore templates,
      EmailDeliveryLogStore logs,
      EmailBounceStore bounces,
      EmailUnsubscribeStore unsubscribes,
      SendGridClientPort sendGrid,
      SesClientPort ses,
      AttachmentFetcherPort attachments,
      CommunicationChannelLookupPort channels,
      PreferenceGatePort preferences,
      UnsubscribeTokenService tokens,
      Clock clock,
      @Value("${medmate.public.base-url:http://localhost:8080}") String publicBaseUrl) {
    this.templates = templates;
    this.logs = logs;
    this.bounces = bounces;
    this.unsubscribes = unsubscribes;
    this.sendGrid = sendGrid;
    this.ses = ses;
    this.attachments = attachments;
    this.channels = channels;
    this.preferences = preferences;
    this.tokens = tokens;
    this.clock = clock;
    String base = publicBaseUrl;
    if (base == null) {
      base = "http://localhost:8080";
    } else if (base.isBlank()) {
      base = "http://localhost:8080";
    } else if (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    this.publicBaseUrl = base;
  }

  public record AttachmentRef(String filename, String url) {}

  public record SendCommand(
      String toEmail,
      String toName,
      String templateId,
      Map<String, Object> variables,
      List<AttachmentRef> attachments,
      UUID customerId) {
    public SendCommand {
      variables =
          variables == null
              ? Map.of()
              : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
      attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
  }

  public Map<String, Object> send(SendCommand cmd) {
    String email = normalizeEmail(cmd.toEmail());
    if (email.isEmpty() || !email.contains("@")) {
      throw new AppException("INVALID_EMAIL", "to_email is required", 400);
    }
    String templateId = cmd.templateId() == null ? "" : cmd.templateId().trim();
    EmailTemplate template =
        templates
            .findById(templateId)
            .orElseThrow(
                () -> new AppException("TEMPLATE_NOT_FOUND", "template_id not in database", 422));
    if (!template.active()) {
      throw new AppException("TEMPLATE_INACTIVE", "Template is deactivated", 422);
    }

    if (bounces.hasHardBounce(email)) {
      throw new AppException("RECIPIENT_HARD_BOUNCED", "Address has a hard bounce record", 422);
    }

    // Non-transactional respect unsubscribes + preference gate.
    if (!template.category().isTransactional() && unsubscribes.isActivelyUnsubscribed(email)) {
      throw new AppException(
          "RECIPIENT_UNSUBSCRIBED", "Recipient unsubscribed from promotional email", 422);
    }
    if (!preferences.allowsEmail(cmd.customerId(), email, template.category().name())) {
      throw new AppException(
          "PREFERENCE_BLOCKED", "Recipient opted out of this email category", 422);
    }

    if (channels.resolveActiveProvider("EMAIL").isEmpty()) {
      throw new AppException("ALL_PROVIDERS_FAILED", "Email channel unavailable", 503);
    }

    UUID logId = Ids.newId();
    Instant now = clock.instant();
    Map<String, Object> vars = new LinkedHashMap<>(cmd.variables());

    String subject = HandlebarsRenderer.render(template.subject(), vars);
    String html = HandlebarsRenderer.render(template.htmlBody(), vars);
    String text =
        template.textBody() == null || template.textBody().isBlank()
            ? HandlebarsRenderer.htmlToPlainText(html)
            : HandlebarsRenderer.render(template.textBody(), vars);

    if (template.category().isTransactional() && !html.contains(TX_NOTICE)) {
      html = html + "<p>" + TX_NOTICE + "</p>";
      text = text + "\n" + TX_NOTICE;
    }

    String unsubscribeUrl = null;
    if (template.category().requiresUnsubscribeLink()) {
      String token = tokens.issue(email, cmd.customerId());
      unsubscribeUrl = publicBaseUrl + "/api/v1/notifications/unsubscribe?token=" + token;
      String footerHtml =
          "<p style=\"font-size:12px;color:#666\"><a href=\""
              + unsubscribeUrl
              + "\">Unsubscribe</a></p>";
      html = html + footerHtml;
      text = text + "\nUnsubscribe: " + unsubscribeUrl;
    }

    html = rewriteLinks(html, logId);
    String openPixel =
        "<img src=\""
            + publicBaseUrl
            + "/api/v1/notifications/email/t/o/"
            + logId
            + "\" width=\"1\" height=\"1\" alt=\"\"/>";
    html = html + openPixel;

    List<SendGridClientPort.Attachment> fetched = fetchAttachments(cmd.attachments());

    Map<String, String> customArgs = Map.of("log_id", logId.toString());
    SendGridClientPort.SendResult primary =
        sendGrid.send(
            new SendGridClientPort.SendRequest(
                email, blankToNull(cmd.toName()), subject, html, text, fetched, customArgs));

    if (primary.success()) {
      return persistSuccess(
          logId,
          email,
          blankToNull(cmd.toName()),
          template.templateId(),
          subject,
          EmailProvider.SENDGRID,
          primary.messageId(),
          false,
          now,
          unsubscribeUrl);
    }

    if (primary.serverError()) {
      List<SesClientPort.Attachment> sesAtt =
          fetched.stream()
              .map(a -> new SesClientPort.Attachment(a.filename(), a.content(), a.contentType()))
              .toList();
      SesClientPort.SendResult fallback =
          ses.send(
              new SesClientPort.SendRequest(
                  email, blankToNull(cmd.toName()), subject, html, text, sesAtt, customArgs));
      if (fallback.success()) {
        return persistSuccess(
            logId,
            email,
            blankToNull(cmd.toName()),
            template.templateId(),
            subject,
            EmailProvider.SES,
            fallback.messageId(),
            true,
            now,
            unsubscribeUrl);
      }
      logs.insert(
          new EmailDeliveryLog(
              logId,
              email,
              blankToNull(cmd.toName()),
              template.templateId(),
              subject,
              EmailProvider.SES,
              true,
              null,
              EmailLogStatus.BOUNCED,
              now,
              null,
              null,
              null,
              null,
              fallback.errorMessage() == null ? primary.errorMessage() : fallback.errorMessage()));
      throw new AppException("ALL_PROVIDERS_FAILED", "SendGrid and SES both failed", 503);
    }

    logs.insert(
        new EmailDeliveryLog(
            logId,
            email,
            blankToNull(cmd.toName()),
            template.templateId(),
            subject,
            EmailProvider.SENDGRID,
            false,
            null,
            EmailLogStatus.BOUNCED,
            now,
            null,
            null,
            null,
            null,
            primary.errorMessage()));
    throw new AppException("ALL_PROVIDERS_FAILED", "SendGrid send failed", 503);
  }

  public Map<String, Object> handleWebhook(List<Map<String, Object>> events) {
    if (events == null || events.isEmpty()) {
      return Map.of("processed", 0);
    }
    int processed = 0;
    Instant now = clock.instant();
    for (Map<String, Object> event : events) {
      if (event == null) {
        continue;
      }
      String type = stringVal(event.get("event"));
      if (type == null) {
        type = stringVal(event.get("type"));
      }
      if (type == null) {
        continue;
      }
      String messageId =
          firstNonBlank(
              stringVal(event.get("sg_message_id")),
              stringVal(event.get("provider_message_id")),
              stringVal(event.get("smtp-id")));
      String email = normalizeEmail(stringVal(event.get("email")));
      switch (type.toLowerCase(Locale.ROOT)) {
        case "delivered" -> {
          if (messageId != null) {
            logs.markDelivered(messageId, now);
            processed++;
          }
        }
        case "bounce" -> {
          String bounceClass =
              firstNonBlank(stringVal(event.get("bounce_type")), stringVal(event.get("type")));
          EmailBounceType bounceType =
              bounceClass != null && bounceClass.toLowerCase(Locale.ROOT).contains("soft")
                  ? EmailBounceType.SOFT
                  : EmailBounceType.HARD;
          if (messageId != null) {
            logs.markBounced(messageId, bounceType, now);
          }
          if (!email.isEmpty()) {
            bounces.insert(
                new EmailBounce(
                    Ids.newId(),
                    email,
                    bounceType,
                    stringVal(event.get("reason")),
                    bounceType == EmailBounceType.HARD,
                    now));
          }
          processed++;
        }
        case "spamreport", "spam" -> {
          if (messageId != null) {
            logs.markSpam(messageId, now);
          }
          if (!email.isEmpty()) {
            unsubscribes.upsertActive(Ids.newId(), email, EmailUnsubscribeSource.SPAM_REPORT, now);
          }
          processed++;
        }
        case "open" -> {
          UUID logId = logIdFromEvent(event);
          if (logId != null) {
            logs.markOpened(logId, now);
            processed++;
          }
        }
        case "click" -> {
          UUID logId = logIdFromEvent(event);
          if (logId != null) {
            logs.markClicked(logId, now);
            processed++;
          }
        }
        default -> {
          // ignore
        }
      }
    }
    return Map.of("processed", processed);
  }

  public Map<String, Object> trackOpen(UUID logId) {
    Instant now = clock.instant();
    logs.markOpened(logId, now);
    return Map.of("opened", true, "log_id", logId.toString());
  }

  public Map<String, Object> trackClick(UUID logId, String targetUrl) {
    Instant now = clock.instant();
    logs.markClicked(logId, now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("clicked", true);
    data.put("log_id", logId.toString());
    data.put("redirect_url", targetUrl);
    return data;
  }

  private List<SendGridClientPort.Attachment> fetchAttachments(List<AttachmentRef> refs) {
    if (refs.isEmpty()) {
      return List.of();
    }
    List<SendGridClientPort.Attachment> out = new ArrayList<>();
    long total = 0;
    for (AttachmentRef ref : refs) {
      if (ref.url() == null || ref.url().isBlank()) {
        continue;
      }
      AttachmentFetcherPort.FetchResult fetched = attachments.fetch(ref.url());
      if (!fetched.found()) {
        log.warn(
            "Attachment fetch failed for {} — sending without attachment: {}",
            ref.filename(),
            fetched.errorMessage());
        continue;
      }
      total += fetched.content().length;
      if (total > MAX_ATTACHMENT_BYTES) {
        throw new AppException("ATTACHMENT_TOO_LARGE", "Total attachment size exceeds 10 MB", 422);
      }
      String filename = ref.filename();
      if (filename == null) {
        filename = "attachment.bin";
      } else if (filename.isBlank()) {
        filename = "attachment.bin";
      }
      String contentType = fetched.contentType();
      if (contentType == null) {
        contentType = "application/octet-stream";
      }
      out.add(new SendGridClientPort.Attachment(filename, fetched.content(), contentType));
    }
    return out;
  }

  /** Rewrite anchor hrefs through the click tracker. */
  String rewriteLinks(String html, UUID logId) {
    if (html == null || html.isBlank()) {
      return html == null ? "" : html;
    }
    java.util.regex.Pattern p =
        java.util.regex.Pattern.compile("(?i)(href\\s*=\\s*[\"'])([^\"']+)([\"'])");
    java.util.regex.Matcher m = p.matcher(html);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
      String original = m.group(2);
      if (original.startsWith(publicBaseUrl + "/api/v1/notifications/")) {
        m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group(0)));
        continue;
      }
      String tracked =
          publicBaseUrl
              + "/api/v1/notifications/email/t/c/"
              + logId
              + "?u="
              + URLEncoder.encode(original, StandardCharsets.UTF_8);
      m.appendReplacement(
          sb, java.util.regex.Matcher.quoteReplacement(m.group(1) + tracked + m.group(3)));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  private Map<String, Object> persistSuccess(
      UUID logId,
      String email,
      String toName,
      String templateId,
      String subject,
      EmailProvider provider,
      String messageId,
      boolean fallbackUsed,
      Instant now,
      String unsubscribeUrl) {
    logs.insert(
        new EmailDeliveryLog(
            logId,
            email,
            toName,
            templateId,
            subject,
            provider,
            fallbackUsed,
            messageId,
            EmailLogStatus.SENT,
            now,
            null,
            null,
            null,
            null,
            null));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("log_id", logId.toString());
    data.put("to_email", email);
    data.put("template_id", templateId);
    data.put("provider", provider.name());
    data.put("provider_message_id", messageId);
    data.put("status", EmailLogStatus.SENT.name());
    data.put("sent_at", now.toString());
    data.put("fallback_used", fallbackUsed);
    if (unsubscribeUrl != null) {
      data.put("unsubscribe_url", unsubscribeUrl);
    }
    return data;
  }

  private static UUID logIdFromEvent(Map<String, Object> event) {
    Object custom = event.get("log_id");
    if (custom == null && event.get("custom_args") instanceof Map<?, ?> args) {
      custom = args.get("log_id");
    }
    if (custom == null) {
      return null;
    }
    try {
      return UUID.fromString(String.valueOf(custom));
    } catch (RuntimeException e) {
      return null;
    }
  }

  static String normalizeEmail(String raw) {
    return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
  }

  private static String blankToNull(String s) {
    if (s == null) {
      return null;
    }
    String trimmed = s.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String stringVal(Object o) {
    if (o == null) {
      return null;
    }
    return String.valueOf(o);
  }

  private static String firstNonBlank(String... values) {
    for (String v : values) {
      if (v == null) {
        continue;
      }
      if (!v.isBlank()) {
        return v;
      }
    }
    return null;
  }
}
