package com.nammamedmate.settings.application;

import com.nammamedmate.kernel.api.PageRequest;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.settings.application.port.out.AuditArchivePort;
import com.nammamedmate.settings.application.port.out.AuditExportEmailPort;
import com.nammamedmate.settings.application.port.out.AuditExportJobStore;
import com.nammamedmate.settings.application.port.out.PlatformAuditLogStore;
import com.nammamedmate.settings.application.port.out.PlatformAuditLogStore.AuditLogRow;
import com.nammamedmate.settings.application.port.out.PlatformAuditLogStore.ListFilter;
import com.nammamedmate.settings.application.port.out.PlatformAuditLogStore.PageResult;
import com.nammamedmate.settings.domain.AuditActorTypes;
import com.nammamedmate.settings.domain.AuditRedaction;
import com.nammamedmate.settings.domain.JsonDiff;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditLogService {

  private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

  private static final int LIST_LIMIT = 20;
  private static final int DETAIL_LIMIT = 30;
  private static final int MINUTE = 60;
  private static final Set<String> SORTS = Set.of("timestamp", "action", "resource_type");
  private static final int ARCHIVE_BATCH = 500;

  private final PlatformAuditLogStore store;
  private final AuditExportJobStore exportJobs;
  private final AuditExportEmailPort exportEmail;
  private final AuditArchivePort archivePort;
  private final RateLimiter rateLimiter;
  private final Clock clock;
  private final Executor auditExecutor;

  public AuditLogService(
      PlatformAuditLogStore store,
      AuditExportJobStore exportJobs,
      AuditExportEmailPort exportEmail,
      AuditArchivePort archivePort,
      RateLimiter rateLimiter,
      Clock clock,
      @Qualifier("auditExecutor") Executor auditExecutor) {
    this.store = store;
    this.exportJobs = exportJobs;
    this.exportEmail = exportEmail;
    this.archivePort = archivePort;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.auditExecutor = auditExecutor;
  }

  public record ListResult(Object data, PaginationMeta meta) {}

  @Transactional(readOnly = true)
  public ListResult list(
      MedmatePrincipal principal,
      Integer page,
      Integer limit,
      String sort,
      String order,
      UUID actorId,
      String actorType,
      String resourceType,
      UUID resourceId,
      String action,
      String from,
      String to,
      Boolean export) {
    requireAnyAdmin(principal);
    rateLimit("admin:audit-log:list:" + principal.subject(), LIST_LIMIT, MINUTE);

    Instant fromTs = parseBound(from, false);
    Instant toTs = parseBound(to, true);
    if (fromTs != null && toTs != null && fromTs.isAfter(toTs)) {
      throw new AppException("VALIDATION_ERROR", "from must be before to", 400);
    }

    String actorTypeNorm = null;
    if (actorType != null && !actorType.isBlank()) {
      try {
        actorTypeNorm = AuditActorTypes.normalize(actorType);
      } catch (IllegalArgumentException ex) {
        throw new AppException("VALIDATION_ERROR", ex.getMessage(), 400);
      }
    }

    String sortField =
        sort == null || sort.isBlank() ? "timestamp" : sort.trim().toLowerCase(Locale.ROOT);
    if (!SORTS.contains(sortField)) {
      throw new AppException(
          "VALIDATION_ERROR", "sort must be one of: timestamp, action, resource_type", 400);
    }
    String orderField =
        order == null || order.isBlank() ? "desc" : order.trim().toLowerCase(Locale.ROOT);
    if (!"asc".equals(orderField) && !"desc".equals(orderField)) {
      throw new AppException("VALIDATION_ERROR", "order must be asc or desc", 400);
    }

    if (Boolean.TRUE.equals(export)) {
      return queueExport(
          principal,
          actorId,
          actorTypeNorm,
          resourceType,
          resourceId,
          action,
          fromTs,
          toTs,
          sortField,
          orderField);
    }

    PageRequest pr = PageRequest.normalize(page, limit, sortField, orderField);
    // Story default order is desc; PageRequest.normalize maps null→asc so pass explicitly.
    if (order == null || order.isBlank()) {
      pr = new PageRequest(pr.page(), pr.limit(), sortField, "desc");
    }

    PageResult result =
        store.list(
            new ListFilter(
                actorId,
                actorTypeNorm,
                blankToNull(resourceType),
                resourceId,
                blankToNull(action),
                fromTs,
                toTs,
                sortField,
                pr.order(),
                pr.limit(),
                pr.offset()));

    List<Map<String, Object>> data = new ArrayList<>(result.rows().size());
    for (AuditLogRow row : result.rows()) {
      data.add(toListItem(row));
    }
    return new ListResult(data, PaginationMeta.of(pr.page(), pr.limit(), result.total()));
  }

  @Transactional(readOnly = true)
  public Map<String, Object> get(MedmatePrincipal principal, UUID id) {
    requireAnyAdmin(principal);
    rateLimit("admin:audit-log:get:" + principal.subject(), DETAIL_LIMIT, MINUTE);
    if (id == null) {
      throw new AppException("AUDIT_LOG_NOT_FOUND", "Audit log entry not found", 404);
    }
    AuditLogRow row =
        store
            .findById(id)
            .orElseThrow(
                () -> new AppException("AUDIT_LOG_NOT_FOUND", "Audit log entry not found", 404));
    Map<String, Object> data = toListItem(row);
    data.put("diff", JsonDiff.diff(row.beforeState(), row.afterState()));
    return data;
  }

  /** SYSTEM actor append for background jobs (AC-2). */
  public void appendSystem(
      String jobName,
      String action,
      String resourceType,
      UUID resourceId,
      Map<String, Object> before,
      Map<String, Object> after) {
    Instant now = clock.instant();
    String name = jobName == null || jobName.isBlank() ? "system-job" : jobName.trim();
    try {
      store.append(
          Ids.newId(),
          null,
          name,
          "SYSTEM",
          AuditActorTypes.SYSTEM,
          action == null ? "system.action" : action.trim(),
          resourceType == null || resourceType.isBlank() ? "system" : resourceType.trim(),
          resourceId,
          AuditRedaction.redactMap(before),
          AuditRedaction.redactMap(after),
          Map.of("source", "system"),
          "0.0.0.0",
          null,
          now);
    } catch (RuntimeException ex) {
      log.warn("Failed SYSTEM audit append action={}: {}", action, ex.toString());
    }
  }

  /** Best-effort middleware append (fire-and-forget caller). */
  public void appendMiddleware(
      UUID actorId,
      String actorName,
      String actorRole,
      String action,
      String resourceType,
      UUID resourceId,
      Map<String, Object> metadata,
      String ipAddress,
      String userAgent) {
    Instant now = clock.instant();
    try {
      store.append(
          Ids.newId(),
          actorId,
          actorName == null || actorName.isBlank() ? "unknown" : actorName,
          actorRole == null || actorRole.isBlank() ? "unknown" : actorRole,
          AuditActorTypes.ADMIN,
          action,
          resourceType,
          resourceId,
          null,
          null,
          metadata == null ? Map.of() : metadata,
          ipAddress == null || ipAddress.isBlank() ? "0.0.0.0" : ipAddress,
          userAgent,
          now);
    } catch (RuntimeException ex) {
      log.warn("Failed middleware audit append action={}: {}", action, ex.toString());
    }
  }

  public void archiveOlderThanTwoYears() {
    Instant cutoff = clock.instant().minus(730, ChronoUnit.DAYS);
    List<AuditLogRow> batch = store.listForArchive(cutoff, ARCHIVE_BATCH);
    Instant now = clock.instant();
    for (AuditLogRow row : batch) {
      try {
        archivePort.archive(row.id(), row.timestamp());
        store.markArchived(row.id(), now);
      } catch (RuntimeException ex) {
        log.warn("Failed to archive audit id={}: {}", row.id(), ex.toString());
      }
    }
  }

  private ListResult queueExport(
      MedmatePrincipal principal,
      UUID actorId,
      String actorType,
      String resourceType,
      UUID resourceId,
      String action,
      Instant from,
      Instant to,
      String sort,
      String order) {
    UUID jobId = Ids.newId();
    Instant now = clock.instant();
    Map<String, Object> filters = new LinkedHashMap<>();
    filters.put("actor_id", actorId == null ? null : actorId.toString());
    filters.put("actor_type", actorType);
    filters.put("resource_type", resourceType);
    filters.put("resource_id", resourceId == null ? null : resourceId.toString());
    filters.put("action", action);
    filters.put("from", from == null ? null : from.toString());
    filters.put("to", to == null ? null : to.toString());
    filters.put("sort", sort);
    filters.put("order", order);
    exportJobs.insertQueued(jobId, filters, now);

    auditExecutor.execute(() -> completeExport(principal.subject(), jobId));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("export_job_id", jobId.toString());
    data.put("status", "QUEUED");
    data.put(
        "message",
        "CSV export is being generated. You will receive an email with the download link.");
    return new ListResult(data, null);
  }

  private void completeExport(UUID actorId, UUID jobId) {
    try {
      String url =
          "https://s3.stub.local/audit-exports/" + jobId + ".csv?X-Amz-Expires=3600&expires_in=1h";
      exportJobs.markCompleted(jobId, url, clock.instant());
      exportEmail.sendExportReady(actorId, jobId, url);
    } catch (RuntimeException ex) {
      log.warn("Failed to complete audit export jobId={}: {}", jobId, ex.toString());
    }
  }

  private Map<String, Object> toListItem(AuditLogRow row) {
    Map<String, Object> actor = new LinkedHashMap<>();
    actor.put("id", row.actorId() == null ? null : row.actorId().toString());
    actor.put("name", row.actorName());
    actor.put("role", row.actorRole());
    actor.put("type", row.actorType());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", row.id().toString());
    data.put("actor", actor);
    data.put("action", row.action());
    data.put("resource_type", row.resourceType());
    data.put("resource_id", row.resourceId() == null ? null : row.resourceId().toString());
    data.put("before_state", row.beforeState());
    data.put("after_state", row.afterState());
    data.put("metadata", row.metadata() == null ? Map.of() : row.metadata());
    data.put("ip_address", row.ipAddress());
    data.put("user_agent", row.userAgent());
    data.put("timestamp", row.timestamp().toString());
    return data;
  }

  private Instant parseBound(String raw, boolean endOfDayIfDate) {
    if (raw == null) {
      return null;
    }
    if (raw.isBlank()) {
      return null;
    }
    String v = raw.trim();
    try {
      return Instant.parse(v);
    } catch (DateTimeParseException ignored) {
      // fall through
    }
    try {
      LocalDate d = LocalDate.parse(v);
      if (endOfDayIfDate) {
        return d.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusNanos(1);
      }
      return d.atStartOfDay().toInstant(ZoneOffset.UTC);
    } catch (DateTimeParseException ex) {
      throw new AppException("VALIDATION_ERROR", "Invalid date range format", 400);
    }
  }

  private void requireAnyAdmin(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (!isAdmin(principal.role())) {
      throw new AppException("FORBIDDEN", "Admin access required", 403);
    }
  }

  private static boolean isAdmin(AuthRole role) {
    return role == AuthRole.ADMIN_SUPER
        || role == AuthRole.ADMIN_OPERATIONS
        || role == AuthRole.ADMIN_FINANCE
        || role == AuthRole.ADMIN_SUPPORT
        || role == AuthRole.ADMIN_COMPLIANCE;
  }

  private void rateLimit(String key, int limit, int windowSeconds) {
    if (!rateLimiter.tryAcquire(key, limit, windowSeconds)) {
      int retry = rateLimiter.secondsUntilAvailable(key, limit, windowSeconds);
      throw new AppException("RATE_LIMITED", "Too many requests", 429, Math.max(retry, 1));
    }
  }

  private static String blankToNull(String v) {
    return v == null || v.isBlank() ? null : v.trim();
  }
}
