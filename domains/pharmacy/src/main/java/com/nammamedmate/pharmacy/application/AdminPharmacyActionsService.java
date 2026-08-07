package com.nammamedmate.pharmacy.application;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.pharmacy.application.port.out.AdminNoteStore;
import com.nammamedmate.pharmacy.application.port.out.AdminNoteStore.NoteRow;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.AdminDetailRow;
import com.nammamedmate.pharmacy.application.port.out.AuditLogStore;
import com.nammamedmate.pharmacy.application.port.out.AuditLogStore.AuditLogRecord;
import com.nammamedmate.pharmacy.application.port.out.NotificationDispatchPort;
import com.nammamedmate.pharmacy.application.port.out.PharmacyCallLogStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyCallLogStore.CallLogRow;
import com.nammamedmate.pharmacy.application.port.out.PharmacyNoticeStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyNoticeStore.NoticeRow;
import com.nammamedmate.pharmacy.domain.CallDurationFormatter;
import com.nammamedmate.pharmacy.domain.WhatsAppTemplateRegistry;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminPharmacyActionsService {

  static final int NOTICE_LIMIT_PER_HOUR = 3;
  static final Duration NOTICE_WINDOW = Duration.ofHours(1);
  private static final int NOTICE_RATE_LIMIT = 30;
  private static final int NOTES_RATE_LIMIT = 60;
  private static final int CALL_LOG_RATE_LIMIT = 30;
  private static final int WINDOW = 60;
  private static final int DEFAULT_PAGE_LIMIT = 20;
  private static final int MAX_PAGE_LIMIT = 100;

  private static final Set<String> CHANNELS = Set.of("WHATSAPP", "EMAIL", "IN_APP", "ALL");
  private static final Set<String> PRIORITIES = Set.of("NORMAL", "URGENT");
  private static final Set<String> CALL_OUTCOMES =
      Set.of("RESOLVED", "FOLLOW_UP_REQUIRED", "NO_ANSWER", "CALLBACK_SCHEDULED", "ESCALATED");

  private final AdminPharmacyStore pharmacies;
  private final PharmacyNoticeStore notices;
  private final AdminNoteStore notes;
  private final PharmacyCallLogStore callLogs;
  private final AuditLogStore auditLog;
  private final NotificationDispatchPort notifications;
  private final RateLimiter rateLimiter;
  private final Clock clock;
  private final ObjectProvider<StringRedisTemplate> redis;

  public AdminPharmacyActionsService(
      AdminPharmacyStore pharmacies,
      PharmacyNoticeStore notices,
      AdminNoteStore notes,
      PharmacyCallLogStore callLogs,
      AuditLogStore auditLog,
      NotificationDispatchPort notifications,
      RateLimiter rateLimiter,
      Clock clock,
      ObjectProvider<StringRedisTemplate> redis) {
    this.pharmacies = pharmacies;
    this.notices = notices;
    this.notes = notes;
    this.callLogs = callLogs;
    this.auditLog = auditLog;
    this.notifications = notifications;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.redis = redis;
  }

  public record NoticeResult(Map<String, Object> data, String skipReason) {
    public NoticeResult {
      if (data != null) {
        data = Map.copyOf(data);
      }
    }
  }

  @Transactional
  public Map<String, Object> sendNotice(
      MedmatePrincipal principal,
      UUID pharmacyId,
      String channel,
      String subject,
      String message,
      String priority,
      String templateName) {
    return sendNoticeInternal(
            principal, pharmacyId, channel, subject, message, priority, templateName, null, true)
        .data();
  }

  @Transactional
  public NoticeResult sendNoticeInternal(
      MedmatePrincipal principal,
      UUID pharmacyId,
      String channel,
      String subject,
      String message,
      String priority,
      String templateName,
      UUID bulkJobId,
      boolean enforceRateLimit) {
    requireNoticeRole(principal);
    if (bulkJobId == null) {
      rateLimit("admin:pharmacies:notice:" + principal.subject(), NOTICE_RATE_LIMIT);
    }
    AdminDetailRow pharmacy = requirePharmacy(pharmacyId);

    String normalizedChannel = normalizeChannel(channel);
    String normalizedPriority = normalizePriority(priority);
    enforceComplianceChannel(principal, normalizedChannel);
    if (principal.role() == AuthRole.ADMIN_SUPPORT && "URGENT".equals(normalizedPriority)) {
      throw new AppException(
          "FORBIDDEN", "admin_support may only send NORMAL priority notices", 403);
    }

    if (message == null || message.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "message is required", 400);
    }
    if (message.length() > 2000) {
      throw new AppException("VALIDATION_ERROR", "message max 2000 chars", 400);
    }

    List<String> resolvedChannels = resolveChannels(normalizedChannel, normalizedPriority);
    if (resolvedChannels.contains("WHATSAPP")) {
      if (templateName == null || templateName.isBlank()) {
        throw new AppException("TEMPLATE_REQUIRED", "template_name required for WhatsApp", 400);
      }
      if (!WhatsAppTemplateRegistry.isApproved(templateName)) {
        throw new AppException("INVALID_TEMPLATE", "template_name is not approved", 400);
      }
    }
    if (resolvedChannels.contains("EMAIL") || resolvedChannels.contains("IN_APP")) {
      if (subject == null || subject.isBlank()) {
        throw new AppException("SUBJECT_REQUIRED", "subject required for EMAIL or IN_APP", 400);
      }
      if (subject.length() > 200) {
        throw new AppException("VALIDATION_ERROR", "subject max 200 chars", 400);
      }
    }

    Instant now = clock.instant();
    Instant windowStart = now.minus(NOTICE_WINDOW);
    int sentInWindow = notices.countSince(pharmacyId, windowStart);
    if (enforceRateLimit && sentInWindow >= NOTICE_LIMIT_PER_HOUR) {
      Instant oldest = notices.oldestSentAtSince(pharmacyId, windowStart);
      Instant resetAt = oldest == null ? now.plus(NOTICE_WINDOW) : oldest.plus(NOTICE_WINDOW);
      int retrySeconds = (int) Math.max(1, ChronoUnit.SECONDS.between(now, resetAt));
      throw new AppException(
          "NOTICE_RATE_LIMIT_EXCEEDED",
          "Maximum 3 notices per hour for this pharmacy",
          429,
          retrySeconds,
          Map.of("rate_limit_reset_at", resetAt.toString()));
    }
    if (!enforceRateLimit && sentInWindow >= NOTICE_LIMIT_PER_HOUR) {
      return new NoticeResult(null, "NOTICE_RATE_LIMIT_EXCEEDED");
    }
    if (!"ACTIVE".equals(pharmacy.status()) && bulkJobId != null) {
      return new NoticeResult(null, "PHARMACY_NOT_ACTIVE");
    }

    UUID noticeId = Ids.newId();
    String trimmedTemplate =
        templateName == null || templateName.isBlank() ? null : templateName.trim();
    notices.insert(
        new NoticeRow(
            noticeId,
            pharmacyId,
            resolvedChannels,
            blankToNull(subject),
            message.trim(),
            trimmedTemplate,
            normalizedPriority,
            principal.subject(),
            now,
            bulkJobId));

    bumpNoticeRateCounter(pharmacyId, now);

    notifications.dispatchPharmacyNotice(
        pharmacyId,
        resolvedChannels,
        trimmedTemplate,
        blankToNull(subject),
        message.trim(),
        normalizedPriority);

    auditNotice(principal, pharmacyId, resolvedChannels, message.trim(), now);

    int remaining = Math.max(0, NOTICE_LIMIT_PER_HOUR - sentInWindow - 1);
    Instant resetAt = now.plus(NOTICE_WINDOW);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("notice_id", noticeId.toString());
    data.put("pharmacy_id", pharmacyId.toString());
    data.put("channels_sent", resolvedChannels);
    data.put("priority", normalizedPriority);
    data.put("sent_at", now.toString());
    data.put("rate_limit_remaining", remaining);
    data.put("rate_limit_reset_at", resetAt.toString());
    return new NoticeResult(data, null);
  }

  @Transactional
  public Map<String, Object> addNote(
      MedmatePrincipal principal, UUID pharmacyId, String note, Boolean flagged) {
    requireNotesWriteRole(principal);
    rateLimit("admin:pharmacies:notes:add:" + principal.subject(), NOTES_RATE_LIMIT);
    requirePharmacy(pharmacyId);

    if (note == null || note.isBlank()) {
      throw new AppException("NOTE_REQUIRED", "note is required", 400);
    }
    if (note.length() > 2000) {
      throw new AppException("VALIDATION_ERROR", "note max 2000 chars", 400);
    }

    boolean isFlagged = flagged != null && flagged;
    Instant now = clock.instant();
    UUID noteId = Ids.newId();
    notes.insert(new NoteRow(noteId, pharmacyId, note.trim(), isFlagged, principal.subject(), now));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("note_id", noteId.toString());
    data.put("pharmacy_id", pharmacyId.toString());
    data.put("note", note.trim());
    data.put("is_flagged", isFlagged);
    data.put("added_by", adminActor(principal));
    data.put("created_at", now.toString());
    return data;
  }

  public record NotesListResult(Map<String, Object> data, PaginationMeta meta) {
    public NotesListResult {
      if (data != null) {
        data = Map.copyOf(data);
      }
    }
  }

  @Transactional(readOnly = true)
  public NotesListResult listNotes(
      MedmatePrincipal principal,
      UUID pharmacyId,
      Boolean flaggedOnly,
      Integer page,
      Integer limit) {
    requireNotesReadRole(principal);
    rateLimit("admin:pharmacies:notes:list:" + principal.subject(), NOTES_RATE_LIMIT);
    requirePharmacy(pharmacyId);

    int p = page == null || page < 1 ? 1 : page;
    int l = limit == null ? DEFAULT_PAGE_LIMIT : Math.min(Math.max(limit, 1), MAX_PAGE_LIMIT);
    int offset = (p - 1) * l;

    List<Map<String, Object>> noteMaps = new ArrayList<>();
    for (NoteRow row : notes.list(pharmacyId, flaggedOnly, l, offset)) {
      noteMaps.add(toNoteMap(row, principal));
    }

    long total = notes.count(pharmacyId, flaggedOnly);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacyId.toString());
    data.put("notes", noteMaps);
    return new NotesListResult(data, PaginationMeta.of(p, l, total));
  }

  @Transactional
  public Map<String, Object> logCall(
      MedmatePrincipal principal,
      UUID pharmacyId,
      Integer durationSeconds,
      String callOutcome,
      String notesText) {
    requireCallLogRole(principal);
    rateLimit("admin:pharmacies:call-log:" + principal.subject(), CALL_LOG_RATE_LIMIT);
    requirePharmacy(pharmacyId);

    if (durationSeconds == null || durationSeconds < 1) {
      throw new AppException("DURATION_REQUIRED", "duration_seconds must be at least 1", 400);
    }
    if (callOutcome == null || !CALL_OUTCOMES.contains(callOutcome)) {
      throw new AppException("INVALID_CALL_OUTCOME", "call_outcome is not valid", 400);
    }
    if (notesText != null && notesText.length() > 1000) {
      throw new AppException("VALIDATION_ERROR", "notes max 1000 chars", 400);
    }

    Instant now = clock.instant();
    UUID callLogId = Ids.newId();
    callLogs.insert(
        new CallLogRow(
            callLogId,
            pharmacyId,
            durationSeconds,
            callOutcome,
            blankToNull(notesText),
            principal.subject(),
            now));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("call_log_id", callLogId.toString());
    data.put("pharmacy_id", pharmacyId.toString());
    data.put("duration_seconds", durationSeconds);
    data.put("duration_formatted", CallDurationFormatter.format(durationSeconds));
    data.put("call_outcome", callOutcome);
    data.put("notes", blankToNull(notesText));
    data.put("logged_by", adminActor(principal));
    data.put("logged_at", now.toString());
    return data;
  }

  static List<String> resolveChannels(String channel, String priority) {
    if ("ALL".equals(channel)) {
      if ("URGENT".equals(priority)) {
        return List.of("WHATSAPP", "EMAIL", "IN_APP");
      }
      return List.of("IN_APP", "WHATSAPP");
    }
    if ("URGENT".equals(priority)) {
      if ("WHATSAPP".equals(channel)) {
        return List.of("WHATSAPP", "EMAIL");
      }
      if ("IN_APP".equals(channel)) {
        return List.of("IN_APP", "WHATSAPP", "EMAIL");
      }
    }
    return List.of(channel);
  }

  private void bumpNoticeRateCounter(UUID pharmacyId, Instant now) {
    StringRedisTemplate template = redis == null ? null : redis.getIfAvailable();
    if (template == null) {
      return;
    }
    long hourEpoch = now.getEpochSecond() / 3600;
    String key = "pharmacy_notice_rate:" + pharmacyId + ":" + hourEpoch;
    Long count = template.opsForValue().increment(key);
    if (count != null && count == 1L) {
      template.expire(key, NOTICE_WINDOW);
    }
  }

  private void auditNotice(
      MedmatePrincipal principal,
      UUID pharmacyId,
      List<String> channels,
      String messageSummary,
      Instant now) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("channel", channels);
    payload.put("pharmacy_id", pharmacyId.toString());
    payload.put("message_summary", truncate(messageSummary, 200));
    auditLog.append(
        new AuditLogRecord(
            Ids.newId(),
            "pharmacy",
            pharmacyId,
            "NOTICE_SENT",
            principal.subject(),
            principal.role().value(),
            payload,
            null,
            now));
  }

  private Map<String, Object> toNoteMap(NoteRow row, MedmatePrincipal reader) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("note_id", row.id().toString());
    map.put("note", row.note());
    map.put("is_flagged", row.flagged());
    map.put(
        "added_by",
        Map.of(
            "admin_id",
            row.addedBy().toString(),
            "name",
            adminDisplayNameForId(row.addedBy(), reader.role()),
            "role",
            reader.role().value()));
    map.put("created_at", row.createdAt().toString());
    return map;
  }

  private Map<String, Object> adminActor(MedmatePrincipal principal) {
    return Map.of(
        "admin_id",
        principal.subject().toString(),
        "name",
        adminDisplayName(principal.role()),
        "role",
        principal.role().value());
  }

  static String adminDisplayName(AuthRole role) {
    switch (role) {
      case ADMIN_SUPER:
        return "Super Administrator";
      case ADMIN_OPERATIONS:
        return "Operations Manager";
      case ADMIN_FINANCE:
        return "Finance Manager";
      case ADMIN_SUPPORT:
        return "Customer Support";
      case ADMIN_COMPLIANCE:
        return "Compliance Officer";
      default:
        return "Admin";
    }
  }

  private static String adminDisplayNameForId(UUID adminId, AuthRole fallbackRole) {
    return adminDisplayName(fallbackRole);
  }

  private AdminDetailRow requirePharmacy(UUID pharmacyId) {
    return pharmacies
        .findDetail(pharmacyId)
        .orElseThrow(() -> new AppException("PHARMACY_NOT_FOUND", "Pharmacy not found", 404));
  }

  private static String normalizeChannel(String channel) {
    if (channel == null || channel.isBlank() || !CHANNELS.contains(channel.trim())) {
      throw new AppException("INVALID_CHANNEL", "channel is not valid", 400);
    }
    return channel.trim();
  }

  private static String normalizePriority(String priority) {
    if (priority == null || priority.isBlank()) {
      return "NORMAL";
    }
    String p = priority.trim();
    if (!PRIORITIES.contains(p)) {
      throw new AppException("VALIDATION_ERROR", "priority must be NORMAL or URGENT", 400);
    }
    return p;
  }

  private static void enforceComplianceChannel(MedmatePrincipal principal, String channel) {
    if (principal.role() == AuthRole.ADMIN_COMPLIANCE && !"EMAIL".equals(channel)) {
      throw new AppException("FORBIDDEN", "admin_compliance may only send EMAIL notices", 403);
    }
  }

  private static void requireNoticeRole(MedmatePrincipal principal) {
    requirePrincipal(principal);
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER
        && role != AuthRole.ADMIN_OPERATIONS
        && role != AuthRole.ADMIN_SUPPORT
        && role != AuthRole.ADMIN_COMPLIANCE) {
      throw new AppException("FORBIDDEN", "Admin role required", 403);
    }
  }

  private static void requireNotesWriteRole(MedmatePrincipal principal) {
    requirePrincipal(principal);
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER
        && role != AuthRole.ADMIN_OPERATIONS
        && role != AuthRole.ADMIN_SUPPORT
        && role != AuthRole.ADMIN_COMPLIANCE
        && role != AuthRole.ADMIN_FINANCE) {
      throw new AppException("FORBIDDEN", "Admin role required", 403);
    }
  }

  private static void requireNotesReadRole(MedmatePrincipal principal) {
    requireNotesWriteRole(principal);
  }

  private static void requireCallLogRole(MedmatePrincipal principal) {
    requirePrincipal(principal);
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER
        && role != AuthRole.ADMIN_OPERATIONS
        && role != AuthRole.ADMIN_SUPPORT) {
      throw new AppException("FORBIDDEN", "Admin role required", 403);
    }
  }

  private static void requirePrincipal(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
  }

  private void rateLimit(String key, int limit) {
    if (!rateLimiter.tryAcquire(key, limit, WINDOW)) {
      throw new AppException("RATE_LIMIT_EXCEEDED", "Too many requests", 429);
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}
