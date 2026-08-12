package com.nammamedmate.automation.application;

import com.nammamedmate.automation.application.port.out.ActionExecutorPort;
import com.nammamedmate.automation.application.port.out.ActivityLogPort;
import com.nammamedmate.automation.application.port.out.ApprovalNotifyPort;
import com.nammamedmate.automation.application.port.out.ApprovalStorePort;
import com.nammamedmate.automation.application.port.out.ApprovalStorePort.ApprovalQueueStats;
import com.nammamedmate.automation.application.port.out.ApprovalStorePort.Chips;
import com.nammamedmate.automation.application.port.out.DeferredExecutionPort;
import com.nammamedmate.automation.application.port.out.KillSwitchPort;
import com.nammamedmate.automation.domain.ApprovalCategory;
import com.nammamedmate.automation.domain.ApprovalRouter;
import com.nammamedmate.automation.domain.ApprovalStatus;
import com.nammamedmate.automation.domain.ApprovalUrgency;
import com.nammamedmate.automation.domain.AutomationApproval;
import com.nammamedmate.automation.domain.DeferredExecution;
import com.nammamedmate.automation.domain.KillSwitchStatus;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApprovalQueueService {

  public static final Duration DEFAULT_TTL = Duration.ofHours(4);

  public record PagedResult(Map<String, Object> data, PaginationMeta meta) {
    public PagedResult {
      data = Map.copyOf(data);
    }
  }

  public record EnqueueCommand(
      UUID ruleId,
      String ruleName,
      UUID triggerEventId,
      String triggerEvent,
      String actionType,
      Map<String, Object> actionParams,
      String entityType,
      UUID entityId,
      String entityName,
      Long amountPaise,
      ApprovalCategory category,
      ApprovalUrgency urgency,
      String why,
      Map<String, Object> triggerContext,
      List<Map<String, Object>> conditionsMet,
      String estimatedImpact,
      String onRejectAction,
      Duration ttl) {

    public EnqueueCommand {
      actionParams = actionParams == null ? Map.of() : Map.copyOf(actionParams);
      triggerContext = triggerContext == null ? Map.of() : Map.copyOf(triggerContext);
      conditionsMet = conditionsMet == null ? List.of() : List.copyOf(conditionsMet);
    }
  }

  private final ApprovalStorePort store;
  private final ApprovalNotifyPort notify;
  private final ActionExecutorPort actions;
  private final ActivityLogPort activityLog;
  private final Clock clock;
  private final Duration ttl;
  private final KillSwitchPort killSwitch;
  private final DeferredExecutionPort deferred;

  public ApprovalQueueService(
      ApprovalStorePort store,
      ApprovalNotifyPort notify,
      ActionExecutorPort actions,
      ActivityLogPort activityLog,
      Clock clock) {
    this(store, notify, actions, activityLog, clock, DEFAULT_TTL, null, null);
  }

  public ApprovalQueueService(
      ApprovalStorePort store,
      ApprovalNotifyPort notify,
      ActionExecutorPort actions,
      ActivityLogPort activityLog,
      Clock clock,
      Duration ttl) {
    this(store, notify, actions, activityLog, clock, ttl, null, null);
  }

  @Autowired
  public ApprovalQueueService(
      ApprovalStorePort store,
      ApprovalNotifyPort notify,
      ActionExecutorPort actions,
      ActivityLogPort activityLog,
      Clock clock,
      @Value("${medmate.automation.approval-ttl:PT4H}") Duration ttl,
      KillSwitchPort killSwitch,
      DeferredExecutionPort deferred) {
    this.store = store;
    this.notify = notify;
    this.actions = actions;
    this.activityLog = activityLog;
    this.clock = clock;
    this.ttl = ttl == null || ttl.isZero() || ttl.isNegative() ? DEFAULT_TTL : ttl;
    this.killSwitch = killSwitch;
    this.deferred = deferred;
  }

  public UUID enqueue(EnqueueCommand cmd) {
    if (cmd.ruleId() != null && cmd.entityId() != null && cmd.actionType() != null) {
      var existing = store.findPending(cmd.ruleId(), cmd.entityId(), cmd.actionType());
      if (existing.isPresent()) {
        AutomationApproval dup = existing.get();
        notify.approvalRequested(
            dup.id(), dup.actionType(), dup.urgency().name(), deepLink(dup.id()));
        return dup.id();
      }
    }
    Instant now = clock.instant();
    Duration window = cmd.ttl() == null ? ttl : cmd.ttl();
    UUID id = Ids.newId();
    UUID logId =
        activityLog.append(
            cmd.actionType(),
            "PENDING_APPROVAL",
            cmd.why() == null ? "Pending approval" : cmd.why(),
            pendingDetail(cmd, now));
    AutomationApproval row =
        new AutomationApproval(
            id,
            cmd.ruleId(),
            cmd.ruleName(),
            cmd.triggerEventId(),
            cmd.triggerEvent(),
            cmd.actionType(),
            cmd.actionParams(),
            cmd.entityType(),
            cmd.entityId(),
            cmd.entityName(),
            cmd.amountPaise(),
            cmd.category() == null ? ApprovalCategory.ADMIN : cmd.category(),
            cmd.urgency() == null ? ApprovalUrgency.NORMAL : cmd.urgency(),
            cmd.why(),
            cmd.triggerContext(),
            cmd.conditionsMet(),
            cmd.estimatedImpact(),
            cmd.onRejectAction(),
            ApprovalStatus.PENDING,
            null,
            null,
            null,
            null,
            logId,
            now,
            now.plus(window),
            null);
    try {
      store.insert(row);
    } catch (DuplicateKeyException dup) {
      var existing = store.findPending(cmd.ruleId(), cmd.entityId(), cmd.actionType());
      if (existing.isPresent()) {
        notify.approvalRequested(
            existing.get().id(),
            existing.get().actionType(),
            existing.get().urgency().name(),
            deepLink(existing.get().id()));
        return existing.get().id();
      }
      throw dup;
    }
    notify.approvalRequested(id, row.actionType(), row.urgency().name(), deepLink(id));
    return id;
  }

  public PagedResult list(
      MedmatePrincipal principal, String status, String urgency, Integer page, Integer limit) {
    requireQueueRead(principal);
    ApprovalStatus st = parseStatus(status);
    ApprovalUrgency urg = parseUrgency(urgency);
    int p = normalizePage(page);
    int lim = normalizeLimit(limit);
    long total = store.count(st, urg);
    List<AutomationApproval> rows = store.list(st, urg, (p - 1) * lim, lim);
    List<Map<String, Object>> items = new ArrayList<>();
    for (AutomationApproval a : rows) {
      items.add(toListItem(a));
    }
    Chips chips = store.chips(clock.instant());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put(
        "chips",
        Map.of(
            "pending_count",
            chips.pendingCount(),
            "urgent_count",
            chips.urgentCount(),
            "approved_today",
            chips.approvedToday(),
            "rejected_today",
            chips.rejectedToday()));
    data.put("approvals", items);
    return new PagedResult(data, PaginationMeta.of(p, lim, total));
  }

  public Map<String, Object> get(MedmatePrincipal principal, UUID id) {
    requireQueueRead(principal);
    return toDetail(require(id));
  }

  @Transactional
  public Map<String, Object> approve(MedmatePrincipal principal, UUID id, String notes) {
    requireQueueRead(principal);
    AutomationApproval a = require(id);
    denyIfExpired(a);
    requirePending(a);
    requireCanResolve(principal, a);
    Instant now = clock.instant();
    Map<String, Object> ctx = executionContext(a, principal, now);
    boolean paused = isPaused();
    UUID activityId = null;
    if (paused) {
      if (deferred != null) {
        deferred.enqueue(id, a.actionType(), a.actionParams(), ctx);
      }
    } else {
      activityId = actions.execute(a.actionType(), a.actionParams(), ctx);
    }
    int updated =
        store.markResolved(
            id,
            ApprovalStatus.PENDING,
            ApprovalStatus.APPROVED,
            principal.subject(),
            notes == null ? null : notes.trim(),
            null,
            activityId,
            now);
    if (updated == 0) {
      throw resolvedOrExpired(require(id));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("approval_id", id);
    data.put("status", ApprovalStatus.APPROVED.name());
    data.put("action_executed", !paused);
    data.put("activity_log_id", activityId);
    data.put("approved_by", principal.subject());
    data.put("approved_at", now.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> reject(MedmatePrincipal principal, UUID id, String reason) {
    requireQueueRead(principal);
    if (reason == null || reason.isBlank()) {
      throw new AppException("REASON_REQUIRED", "reason is required", 400);
    }
    AutomationApproval a = require(id);
    requirePending(a);
    requireCanResolve(principal, a);
    Instant now = clock.instant();
    boolean altFired = fireAlternative(a, principal, now);
    UUID logId =
        activityLog.append(
            a.actionType(),
            "REJECTED",
            reason.trim(),
            humanDetail(a, principal, now, reason.trim()));
    int updated =
        store.markResolved(
            id,
            ApprovalStatus.PENDING,
            ApprovalStatus.REJECTED,
            principal.subject(),
            null,
            reason.trim(),
            logId,
            now);
    if (updated == 0) {
      throw resolvedOrExpired(require(id));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("approval_id", id);
    data.put("status", ApprovalStatus.REJECTED.name());
    data.put("alternative_action_fired", altFired);
    data.put("rejected_by", principal.subject());
    data.put("rejected_at", now.toString());
    return data;
  }

  public Map<String, Object> stats(MedmatePrincipal principal) {
    requireAdmin(principal);
    ApprovalQueueStats s = store.stats(clock.instant());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("avg_response_time_minutes", s.avgResponseTimeMinutes());
    data.put("approval_rate_pct", s.approvalRatePct());
    data.put("rejection_rate_pct", s.rejectionRatePct());
    data.put("expiry_rate_pct", s.expiryRatePct());
    data.put("top_pending_categories", s.topPendingCategories());
    data.put("period_days", 7);
    return data;
  }

  public long pendingCount() {
    return store.countPending();
  }

  public int flushDeferred() {
    if (deferred == null) {
      return 0;
    }
    int n = 0;
    for (DeferredExecution row : deferred.listAll()) {
      try {
        actions.execute(row.actionType(), row.actionParams(), row.executionContext());
        n++;
      } catch (RuntimeException ex) {
        // keep going; delete so we do not replay a poison payload forever
      }
      deferred.delete(row.id());
    }
    return n;
  }

  @Transactional
  public int expireDue(int limit) {
    Instant now = clock.instant();
    int n = 0;
    for (AutomationApproval a : store.listExpired(now, limit)) {
      int updated =
          store.markResolved(
              a.id(),
              ApprovalStatus.PENDING,
              ApprovalStatus.EXPIRED,
              null,
              null,
              "expired",
              a.activityLogId(),
              now);
      if (updated == 0) {
        continue;
      }
      fireAlternative(a, null, now);
      notify.approvalExpired(a.id(), a.actionType());
      n++;
    }
    return n;
  }

  private void denyIfExpired(AutomationApproval a) {
    if (a.status() == ApprovalStatus.EXPIRED
        || (a.status() == ApprovalStatus.PENDING
            && a.expiresAt() != null
            && !a.expiresAt().isAfter(clock.instant()))) {
      if (a.status() == ApprovalStatus.PENDING) {
        store.markResolved(
            a.id(),
            ApprovalStatus.PENDING,
            ApprovalStatus.EXPIRED,
            null,
            null,
            "expired",
            a.activityLogId(),
            clock.instant());
      }
      throw new AppException("APPROVAL_EXPIRED", "Approval has expired", 410);
    }
  }

  private static void requirePending(AutomationApproval a) {
    if (a.status() != ApprovalStatus.PENDING) {
      if (a.status() == ApprovalStatus.EXPIRED) {
        throw new AppException("APPROVAL_EXPIRED", "Approval has expired", 410);
      }
      throw new AppException("APPROVAL_ALREADY_RESOLVED", "Approval is not pending", 409);
    }
  }

  private static AppException resolvedOrExpired(AutomationApproval a) {
    if (a.status() == ApprovalStatus.EXPIRED) {
      return new AppException("APPROVAL_EXPIRED", "Approval has expired", 410);
    }
    return new AppException("APPROVAL_ALREADY_RESOLVED", "Approval is not pending", 409);
  }

  private boolean fireAlternative(AutomationApproval a, MedmatePrincipal principal, Instant now) {
    if (a.onRejectAction() == null || a.onRejectAction().isBlank()) {
      return false;
    }
    Map<String, Object> ctx = executionContext(a, principal, now);
    if (isPaused()) {
      if (deferred != null) {
        deferred.enqueue(a.id(), a.onRejectAction().trim(), a.actionParams(), ctx);
      }
      return false;
    }
    try {
      actions.execute(a.onRejectAction(), a.actionParams(), ctx);
      return true;
    } catch (RuntimeException ex) {
      return false;
    }
  }

  private boolean isPaused() {
    return killSwitch != null && killSwitch.status() == KillSwitchStatus.PAUSED;
  }

  private Map<String, Object> executionContext(
      AutomationApproval a, MedmatePrincipal principal, Instant now) {
    Map<String, Object> ctx = new LinkedHashMap<>(a.triggerContext());
    ctx.put("entity_type", a.entityType());
    if (a.entityId() != null) {
      ctx.put("entity_id", a.entityId().toString());
    }
    if (a.entityName() != null) {
      ctx.put("entity_name", a.entityName());
    }
    if (a.ruleId() != null) {
      ctx.put("rule_id", a.ruleId().toString());
    }
    if (a.triggerEventId() != null) {
      ctx.put("trigger_event_id", a.triggerEventId().toString());
    }
    ctx.put("triggered_at", a.triggeredAt() == null ? now.toString() : a.triggeredAt().toString());
    ctx.put("executed_at", now.toString());
    ctx.put("actor", "HUMAN");
    if (principal != null) {
      ctx.put("override_by", principal.subject().toString());
    }
    if (a.activityLogId() != null) {
      ctx.put("references_action_id", a.activityLogId().toString());
    }
    ctx.put("conditions_evaluated", a.conditionsMet());
    return ctx;
  }

  private static Map<String, Object> pendingDetail(EnqueueCommand cmd, Instant now) {
    Map<String, Object> d = new LinkedHashMap<>();
    if (cmd.ruleId() != null) {
      d.put("rule_id", cmd.ruleId().toString());
    }
    if (cmd.ruleName() != null) {
      d.put("rule_name", cmd.ruleName());
    }
    d.put("entity_type", cmd.entityType() == null ? "UNKNOWN" : cmd.entityType());
    if (cmd.entityId() != null) {
      d.put("entity_id", cmd.entityId().toString());
    }
    if (cmd.entityName() != null) {
      d.put("entity_name", cmd.entityName());
    }
    if (cmd.triggerEventId() != null) {
      d.put("trigger_event_id", cmd.triggerEventId().toString());
    }
    if (cmd.triggerEvent() != null) {
      d.put("trigger_id", cmd.triggerEvent());
    }
    d.put("triggered_at", now.toString());
    d.put("params", cmd.actionParams());
    d.put("actor", "AUTOMATION");
    d.put("conditions_evaluated", cmd.conditionsMet());
    return d;
  }

  private static Map<String, Object> humanDetail(
      AutomationApproval a, MedmatePrincipal principal, Instant now, String reason) {
    Map<String, Object> d = new LinkedHashMap<>();
    if (a.ruleId() != null) {
      d.put("rule_id", a.ruleId().toString());
    }
    d.put("entity_type", a.entityType());
    if (a.entityId() != null) {
      d.put("entity_id", a.entityId().toString());
    }
    if (a.entityName() != null) {
      d.put("entity_name", a.entityName());
    }
    d.put("actor", "HUMAN");
    d.put("override_by", principal.subject().toString());
    d.put("triggered_at", now.toString());
    d.put("executed_at", now.toString());
    if (a.activityLogId() != null) {
      d.put("references_action_id", a.activityLogId().toString());
    }
    d.put("params", Map.of("reason", reason));
    return d;
  }

  private AutomationApproval require(UUID id) {
    return store
        .findById(id)
        .orElseThrow(() -> new AppException("APPROVAL_NOT_FOUND", "Approval not found", 404));
  }

  private static Map<String, Object> toListItem(AutomationApproval a) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("approval_id", a.id());
    row.put("rule_name", a.ruleName() == null ? "" : a.ruleName());
    row.put("action_type", a.actionType());
    row.put("entity_type", a.entityType());
    row.put("entity_id", a.entityId());
    row.put("entity_name", a.entityName());
    row.put("amount_rs", a.amountPaise() == null ? null : ApprovalRouter.rupees(a.amountPaise()));
    row.put("urgency", a.urgency().name());
    row.put("triggered_at", a.triggeredAt().toString());
    row.put("expires_at", a.expiresAt().toString());
    row.put("status", a.status().name());
    return row;
  }

  private static Map<String, Object> toDetail(AutomationApproval a) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("approval_id", a.id());
    data.put("rule_name", a.ruleName() == null ? "" : a.ruleName());
    Map<String, Object> ctx = new LinkedHashMap<>();
    ctx.put("trigger_event", a.triggerEvent());
    ctx.put("entity_type", a.entityType());
    ctx.put("entity_id", a.entityId());
    ctx.put("entity_name", a.entityName());
    ctx.put("payload", a.triggerContext());
    data.put("trigger_context", ctx);
    data.put("conditions_met", a.conditionsMet());
    Map<String, Object> proposed = new LinkedHashMap<>();
    proposed.put("action_type", a.actionType());
    proposed.put("params", a.actionParams());
    data.put("proposed_action", proposed);
    data.put("estimated_impact", a.estimatedImpact());
    data.put("why_requires_approval", a.whyRequiresApproval());
    data.put("urgency", a.urgency().name());
    data.put("triggered_at", a.triggeredAt().toString());
    data.put("expires_at", a.expiresAt().toString());
    data.put("status", a.status().name());
    return data;
  }

  private static ApprovalStatus parseStatus(String raw) {
    if (raw == null || raw.isBlank()) {
      return ApprovalStatus.PENDING;
    }
    try {
      return ApprovalStatus.parse(raw);
    } catch (RuntimeException ex) {
      throw new AppException("VALIDATION_ERROR", "Invalid status filter", 422);
    }
  }

  private static ApprovalUrgency parseUrgency(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return ApprovalUrgency.parse(raw);
    } catch (RuntimeException ex) {
      throw new AppException("VALIDATION_ERROR", "Invalid urgency filter", 422);
    }
  }

  private static int normalizePage(Integer page) {
    if (page == null || page < 1) {
      return 1;
    }
    return page;
  }

  private static int normalizeLimit(Integer limit) {
    if (limit == null || limit < 1) {
      return 20;
    }
    return Math.min(limit, 100);
  }

  private static String deepLink(UUID id) {
    return "/admin/automation/approvals/" + id;
  }

  private static void requireQueueRead(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER
        && role != AuthRole.ADMIN_OPERATIONS
        && role != AuthRole.ADMIN_FINANCE) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
  }

  private static void requireAdmin(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (principal.role() != AuthRole.ADMIN_SUPER && principal.role() != AuthRole.ADMIN_OPERATIONS) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
  }

  private static void requireCanResolve(MedmatePrincipal principal, AutomationApproval a) {
    if (principal.role() != AuthRole.ADMIN_FINANCE) {
      return;
    }
    if (a.category() != ApprovalCategory.FINANCE) {
      throw new AppException("FORBIDDEN", "Finance can only resolve FINANCE approvals", 403);
    }
  }
}
