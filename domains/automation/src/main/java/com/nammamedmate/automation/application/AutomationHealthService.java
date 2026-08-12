package com.nammamedmate.automation.application;

import com.nammamedmate.automation.application.port.out.ActivityLogPort;
import com.nammamedmate.automation.application.port.out.ApprovalStorePort;
import com.nammamedmate.automation.application.port.out.CircuitBreakerPort;
import com.nammamedmate.automation.application.port.out.KillSwitchPort;
import com.nammamedmate.automation.application.port.out.RuleStorePort;
import com.nammamedmate.automation.domain.ActivityStats;
import com.nammamedmate.automation.domain.CircuitBreakerState;
import com.nammamedmate.automation.domain.KillSwitchAction;
import com.nammamedmate.automation.domain.KillSwitchChange;
import com.nammamedmate.automation.domain.KillSwitchStatus;
import com.nammamedmate.automation.domain.RuleHealthMetrics;
import com.nammamedmate.automation.domain.RuleStatus;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AutomationHealthService {

  static final Duration DASHBOARD_CACHE_TTL = Duration.ofSeconds(60);
  static final int ESTIMATED_EFFECT_SECONDS = 60;

  private final KillSwitchPort killSwitch;
  private final ActivityLogPort activity;
  private final RuleStorePort rules;
  private final ApprovalStorePort approvals;
  private final CircuitBreakerPort circuits;
  private final ApprovalQueueService queue;
  private final Clock clock;

  private volatile Map<String, Object> dashboardCache;
  private volatile Instant dashboardCachedAt = Instant.EPOCH;

  public AutomationHealthService(
      KillSwitchPort killSwitch,
      ActivityLogPort activity,
      RuleStorePort rules,
      ApprovalStorePort approvals,
      CircuitBreakerPort circuits,
      Clock clock) {
    this(killSwitch, activity, rules, approvals, circuits, null, clock);
  }

  @Autowired
  public AutomationHealthService(
      KillSwitchPort killSwitch,
      ActivityLogPort activity,
      RuleStorePort rules,
      ApprovalStorePort approvals,
      CircuitBreakerPort circuits,
      ApprovalQueueService queue,
      Clock clock) {
    this.killSwitch = killSwitch;
    this.activity = activity;
    this.rules = rules;
    this.approvals = approvals;
    this.circuits = circuits;
    this.queue = queue;
    this.clock = clock;
  }

  public Map<String, Object> dashboard(MedmatePrincipal principal) {
    requireAdmin(principal);
    Instant now = clock.instant();
    Map<String, Object> hit = dashboardCache;
    if (hit != null && now.isBefore(dashboardCachedAt.plus(DASHBOARD_CACHE_TTL))) {
      return new LinkedHashMap<>(hit);
    }
    Map<String, Object> data = computeDashboard(now);
    dashboardCache = data;
    dashboardCachedAt = now;
    return new LinkedHashMap<>(data);
  }

  public Map<String, Object> perRule(MedmatePrincipal principal) {
    requireAdmin(principal);
    Instant since = clock.instant().minus(Duration.ofHours(24));
    List<Map<String, Object>> rows = new ArrayList<>();
    for (RuleHealthMetrics m : activity.perRuleHealth(since)) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("rule_id", m.ruleId());
      row.put("name", m.name());
      row.put("status", m.status());
      row.put("fire_count_24h", m.fireCount24h());
      row.put("success_rate_pct", m.successRatePct());
      row.put("exception_count_24h", m.exceptionCount24h());
      row.put("last_error", m.lastError());
      row.put("last_error_at", m.lastErrorAt() == null ? null : m.lastErrorAt().toString());
      row.put("avg_execution_ms", m.avgExecutionMs());
      row.put("last_fired_at", m.lastFiredAt() == null ? null : m.lastFiredAt().toString());
      rows.add(row);
    }
    return Map.of("rules", rows);
  }

  public Map<String, Object> circuitBreakers(MedmatePrincipal principal) {
    requireAdmin(principal);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (CircuitBreakerState s : circuits.list()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("action_type", s.actionType());
      row.put("fires_last_hour", s.firesLastHour());
      row.put("threshold", s.thresholdPerHour());
      row.put("circuit_status", s.status().name());
      row.put("opened_at", s.openedAt() == null ? null : s.openedAt().toString());
      row.put("reset_at", s.resetAt() == null ? null : s.resetAt().toString());
      rows.add(row);
    }
    return Map.of("circuit_breakers", rows);
  }

  @Transactional
  public Map<String, Object> toggle(MedmatePrincipal principal, String actionRaw, String reason) {
    requireSuper(principal);
    if (reason == null || reason.isBlank()) {
      throw new AppException("REASON_REQUIRED", "reason is required", 400);
    }
    KillSwitchAction action;
    try {
      action = KillSwitchAction.parse(actionRaw);
    } catch (RuntimeException ex) {
      throw new AppException("VALIDATION_ERROR", "action must be PAUSE or RESUME", 400);
    }
    KillSwitchStatus next = action.toStatus();
    if (killSwitch.status() == next) {
      throw new AppException("ALREADY_IN_STATE", "Kill switch is already " + next.name(), 409);
    }
    Instant now = clock.instant();
    killSwitch.setStatus(next, principal.subject(), reason.trim());
    dashboardCache = null;
    dashboardCachedAt = Instant.EPOCH;
    if (action == KillSwitchAction.RESUME && queue != null) {
      queue.flushDeferred();
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("kill_switch_status", next.name());
    data.put("action", action.name());
    data.put("executed_by", principal.subject());
    data.put("executed_at", now.toString());
    data.put("reason", reason.trim());
    data.put("estimated_effect_within_seconds", ESTIMATED_EFFECT_SECONDS);
    return data;
  }

  void invalidateDashboardCache() {
    dashboardCache = null;
    dashboardCachedAt = Instant.EPOCH;
  }

  private Map<String, Object> computeDashboard(Instant now) {
    ActivityStats s = activity.stats(now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rules_active", rules.countByStatus(RuleStatus.ACTIVE));
    data.put("rules_simulating", rules.countByStatus(RuleStatus.SIMULATING));
    data.put("rules_inactive", rules.countByStatus(RuleStatus.INACTIVE));
    data.put("actions_today", s.actionsLast24h());
    data.put("actions_this_week", s.actionsThisWeek());
    data.put("manual_actions_saved_estimate", s.manualActionsSavedEstimate());
    data.put("exceptions_raised_today", s.exceptionsRaisedToday());
    data.put(
        "pending_approvals",
        approvals == null ? s.pendingApprovalsCount() : approvals.countPending());
    data.put("kill_switch_status", killSwitch.status().name());
    data.put("last_kill_switch_change", lastChangeMap());
    data.put("data_as_of", now.toString());
    return data;
  }

  private Map<String, Object> lastChangeMap() {
    return killSwitch.lastChange().map(AutomationHealthService::toChangeMap).orElse(null);
  }

  private static Map<String, Object> toChangeMap(KillSwitchChange c) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("action", c.action().name());
    m.put("changed_by", c.changedByLabel());
    m.put("changed_at", c.changedAt().toString());
    m.put("reason", c.reason());
    return m;
  }

  private static void requireAdmin(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (principal.role() != AuthRole.ADMIN_SUPER && principal.role() != AuthRole.ADMIN_OPERATIONS) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
  }

  private static void requireSuper(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (principal.role() != AuthRole.ADMIN_SUPER) {
      throw new AppException("FORBIDDEN", "Only admin_super can use the kill switch", 403);
    }
  }
}
