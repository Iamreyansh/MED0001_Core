package com.nammamedmate.automation.application;

import com.nammamedmate.automation.application.port.out.ActionExecutorPort;
import com.nammamedmate.automation.application.port.out.ActivityLogPort;
import com.nammamedmate.automation.application.port.out.ActivityLogPort.ActivityQuery;
import com.nammamedmate.automation.application.port.out.ApprovalStorePort;
import com.nammamedmate.automation.application.port.out.RuleStorePort;
import com.nammamedmate.automation.domain.ActivityLogEntry;
import com.nammamedmate.automation.domain.ActivityStats;
import com.nammamedmate.automation.domain.ActivityStatus;
import com.nammamedmate.automation.domain.RollbackableActions;
import com.nammamedmate.automation.domain.RuleStatus;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActivityLogService {

  public record PagedResult(Map<String, Object> data, PaginationMeta meta) {
    public PagedResult {
      data = Map.copyOf(data);
    }
  }

  private final ActivityLogPort store;
  private final RuleStorePort rules;
  private final ActionExecutorPort actions;
  private final ApprovalStorePort approvals;
  private final Clock clock;

  public ActivityLogService(
      ActivityLogPort store, RuleStorePort rules, ActionExecutorPort actions, Clock clock) {
    this(store, rules, actions, null, clock);
  }

  @Autowired
  public ActivityLogService(
      ActivityLogPort store,
      RuleStorePort rules,
      ActionExecutorPort actions,
      ApprovalStorePort approvals,
      Clock clock) {
    this.store = store;
    this.rules = rules;
    this.actions = actions;
    this.approvals = approvals;
    this.clock = clock;
  }

  public PagedResult list(
      MedmatePrincipal principal,
      String status,
      UUID ruleId,
      String triggerCategory,
      String entityType,
      String dateFrom,
      String dateTo,
      Integer page,
      Integer limit) {
    requireRead(principal);
    if (status != null && !status.isBlank()) {
      try {
        ActivityStatus.parse(status);
      } catch (RuntimeException ex) {
        throw new AppException("VALIDATION_ERROR", "Invalid status filter", 422);
      }
    }
    Instant from = parseInstant(dateFrom, "date_from");
    Instant to = parseInstant(dateTo, "date_to");
    ActivityQuery query =
        new ActivityQuery(
            blankToNull(status),
            ruleId,
            blankToNull(triggerCategory),
            blankToNull(entityType),
            from,
            to,
            financeTypes(principal));
    int p = normalizePage(page);
    int lim = normalizeLimit(limit);
    long total = store.count(query);
    List<ActivityLogEntry> rows = store.list(query, (p - 1) * lim, lim);
    List<Map<String, Object>> items = new ArrayList<>();
    for (ActivityLogEntry e : rows) {
      items.add(toListItem(e));
    }
    return new PagedResult(Map.of("activity", items), PaginationMeta.of(p, lim, total));
  }

  public Map<String, Object> get(MedmatePrincipal principal, UUID actionId) {
    requireRead(principal);
    ActivityLogEntry e = requireEntry(actionId);
    denyFinanceIfNeeded(principal, e);
    return toDetail(e);
  }

  @Transactional
  public Map<String, Object> rollback(MedmatePrincipal principal, UUID actionId, String reason) {
    requireRollback(principal);
    if (reason == null || reason.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "reason is required", 422);
    }
    ActivityLogEntry original = requireEntry(actionId);
    if (store.existsRollbackFor(actionId)) {
      throw new AppException("ALREADY_ROLLED_BACK", "Action has already been rolled back", 422);
    }
    if (original.status() != ActivityStatus.EXECUTED
        || !RollbackableActions.isRollbackable(original.actionType())) {
      throw new AppException("NOT_ROLLBACKABLE", "Action type is not rollbackable", 422);
    }
    Instant now = clock.instant();
    if ("suspend_entity".equals(original.actionType())) {
      Map<String, Object> params = new LinkedHashMap<>(original.actionParams());
      params.put("reason", reason.trim());
      actions.execute(
          "reactivate_entity",
          params,
          Map.of(
              "entity_type",
              original.entityType(),
              "entity_id",
              original.entityId() == null ? "" : original.entityId().toString()));
    }
    Map<String, Object> rollbackParams = new LinkedHashMap<>();
    rollbackParams.put("reason", reason.trim());
    rollbackParams.put("rolled_back_action", original.actionType());
    UUID rollbackId =
        store.append(
            "ROLLBACK",
            ActivityStatus.ROLLED_BACK.name(),
            reason.trim(),
            Map.of(
                "rule_id",
                original.ruleId() == null ? "" : original.ruleId().toString(),
                "entity_type",
                original.entityType(),
                "entity_id",
                original.entityId() == null ? "" : original.entityId().toString(),
                "entity_name",
                original.entityName() == null ? "" : original.entityName(),
                "actor",
                "HUMAN",
                "override_by",
                principal.subject().toString(),
                "triggered_at",
                now.toString(),
                "executed_at",
                now.toString(),
                "references_action_id",
                actionId.toString(),
                "params",
                rollbackParams));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rollback_action_id", rollbackId);
    data.put("original_action_id", actionId);
    data.put("action_type", "ROLLBACK");
    data.put("rolled_back_action", original.actionType());
    data.put("entity_type", original.entityType());
    data.put("entity_id", original.entityId());
    data.put("result", RollbackableActions.rollbackResult(original.actionType()));
    data.put("executed_at", now.toString());
    return data;
  }

  public Map<String, Object> stats(MedmatePrincipal principal) {
    requireAdmin(principal);
    Instant now = clock.instant();
    ActivityStats s = store.stats(now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rules_active", rules.countByStatus(RuleStatus.ACTIVE));
    data.put("rules_simulating", rules.countByStatus(RuleStatus.SIMULATING));
    data.put("rules_inactive", rules.countByStatus(RuleStatus.INACTIVE));
    data.put("actions_last_24h", s.actionsLast24h());
    data.put("actions_this_week", s.actionsThisWeek());
    data.put("manual_actions_saved_estimate", s.manualActionsSavedEstimate());
    data.put("exceptions_raised_today", s.exceptionsRaisedToday());
    data.put(
        "pending_approvals_count",
        approvals == null ? s.pendingApprovalsCount() : approvals.countPending());
    data.put("last_action_at", s.lastActionAt() == null ? null : s.lastActionAt().toString());
    return data;
  }

  private ActivityLogEntry requireEntry(UUID actionId) {
    return store
        .findById(actionId)
        .orElseThrow(() -> new AppException("ACTION_NOT_FOUND", "Action not found", 404));
  }

  private void denyFinanceIfNeeded(MedmatePrincipal principal, ActivityLogEntry e) {
    if (principal.role() != AuthRole.ADMIN_FINANCE) {
      return;
    }
    if (RollbackableActions.isFinancial(e.actionType())) {
      return;
    }
    if ("ROLLBACK".equals(e.actionType())) {
      Object rolled = e.actionParams().get("rolled_back_action");
      if (rolled != null && RollbackableActions.isFinancial(String.valueOf(rolled))) {
        return;
      }
    }
    throw new AppException("ACTION_NOT_FOUND", "Action not found", 404);
  }

  private static Set<String> financeTypes(MedmatePrincipal principal) {
    if (principal.role() == AuthRole.ADMIN_FINANCE) {
      return RollbackableActions.FINANCIAL;
    }
    return null;
  }

  private Map<String, Object> toListItem(ActivityLogEntry e) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("action_id", e.id());
    String name = e.ruleName() == null ? "" : e.ruleName();
    if (e.status() == ActivityStatus.SIMULATED && !name.contains("[SIMULATED]")) {
      name = name.isBlank() ? "[SIMULATED]" : name + " [SIMULATED]";
    }
    row.put("rule_name", name);
    row.put("trigger_event", e.triggerEvent());
    row.put("entity_type", e.entityType());
    row.put("entity_id", e.entityId());
    row.put("entity_name", e.entityName());
    row.put("action_type", e.actionType());
    row.put("action_params", e.actionParams());
    row.put("status", e.status().name());
    row.put("actor", e.actor());
    row.put("triggered_at", e.triggeredAt().toString());
    row.put("executed_at", e.executedAt() == null ? null : e.executedAt().toString());
    row.put("override_by", e.overrideBy());
    row.put("rolled_back", e.rolledBack());
    return row;
  }

  private Map<String, Object> toDetail(ActivityLogEntry e) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("action_id", e.id());
    data.put("rule_id", e.ruleId());
    data.put("rule_name", e.ruleName());
    data.put("trigger_event_id", e.triggerEventId());
    Map<String, Object> te = new LinkedHashMap<>();
    te.put("trigger_id", e.triggerEvent());
    te.put("entity_type", e.entityType());
    te.put("entity_id", e.entityId());
    te.put("payload", e.triggerPayload());
    te.put("fired_at", e.triggerFiredAt() == null ? null : e.triggerFiredAt().toString());
    data.put("trigger_event", te);
    data.put("conditions_evaluated", e.conditionsEvaluated());
    data.put("action_type", e.actionType());
    data.put("action_params", e.actionParams());
    data.put("before_state", e.beforeState());
    data.put("after_state", e.afterState());
    data.put("status", e.status().name());
    data.put("actor", e.actor());
    data.put("triggered_at", e.triggeredAt().toString());
    data.put("executed_at", e.executedAt() == null ? null : e.executedAt().toString());
    data.put("execution_ms", e.executionMs());
    data.put("rolled_back", e.rolledBack());
    data.put("rollback_action_id", e.rollbackActionId());
    return data;
  }

  private static Instant parseInstant(String raw, String field) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(raw.trim());
    } catch (DateTimeParseException ex) {
      throw new AppException("VALIDATION_ERROR", "Invalid " + field, 422);
    }
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
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

  private static void requireRead(MedmatePrincipal principal) {
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

  private static void requireRollback(MedmatePrincipal principal) {
    requireAdmin(principal);
  }

  private static void requireAdmin(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (principal.role() != AuthRole.ADMIN_SUPER && principal.role() != AuthRole.ADMIN_OPERATIONS) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
  }
}
