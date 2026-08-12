package com.nammamedmate.automation.application;

import com.nammamedmate.automation.application.port.out.ActionRegistryPort;
import com.nammamedmate.automation.application.port.out.RuleAuditPort;
import com.nammamedmate.automation.application.port.out.RuleStorePort;
import com.nammamedmate.automation.application.port.out.SeedCatalogPort;
import com.nammamedmate.automation.application.port.out.TriggerRegistryPort;
import com.nammamedmate.automation.domain.ActionSpec;
import com.nammamedmate.automation.domain.AutomationRule;
import com.nammamedmate.automation.domain.ConditionSpec;
import com.nammamedmate.automation.domain.Guardrails;
import com.nammamedmate.automation.domain.RuleStatus;
import com.nammamedmate.automation.domain.SeedCatalogEntry;
import com.nammamedmate.automation.domain.SeedDefinitions;
import com.nammamedmate.automation.domain.TriggerDefinition;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RuleManagementService {

  public static final int MAX_ACTIVE_RULES = 200;

  private static final Set<String> GLOBAL_OPERATORS =
      Set.of("eq", "not_eq", "in", "lt", "amount_lt");

  private final RuleStorePort store;
  private final TriggerRegistryPort triggers;
  private final ActionRegistryPort actions;
  private final RuleAuditPort audit;
  private final ActiveRuleCache cache;
  private final SeedCatalogPort seeds;
  private final Clock clock;

  public RuleManagementService(
      RuleStorePort store,
      TriggerRegistryPort triggers,
      ActionRegistryPort actions,
      RuleAuditPort audit,
      ActiveRuleCache cache,
      SeedCatalogPort seeds,
      Clock clock) {
    this.store = store;
    this.triggers = triggers;
    this.actions = actions;
    this.audit = audit;
    this.cache = cache;
    this.seeds = seeds;
    this.clock = clock;
  }

  public record PagedResult(Map<String, Object> data, PaginationMeta meta) {
    public PagedResult {
      data = Map.copyOf(data);
    }
  }

  public PagedResult list(
      MedmatePrincipal principal,
      String status,
      String triggerCategory,
      String search,
      Integer page,
      Integer limit) {
    requireAdmin(principal);
    if (status != null && !status.isBlank()) {
      try {
        RuleStatus.parse(status);
      } catch (IllegalArgumentException ex) {
        throw new AppException("VALIDATION_ERROR", "Invalid status filter", 422);
      }
    }
    int p = normalizePage(page);
    int lim = normalizeLimit(limit);
    long total = store.countFiltered(status, triggerCategory, search);
    List<AutomationRule> rows =
        store.listFiltered(status, triggerCategory, search, (p - 1) * lim, lim);
    List<Map<String, Object>> items = new ArrayList<>();
    for (AutomationRule r : rows) {
      items.add(listItem(r));
    }
    return new PagedResult(Map.of("rules", items), PaginationMeta.of(p, lim, total));
  }

  @Transactional
  public Map<String, Object> create(
      MedmatePrincipal principal,
      String name,
      String description,
      String triggerId,
      Map<String, Object> triggerParams,
      List<ConditionSpec> conditions,
      List<ActionSpec> actionSpecs,
      Guardrails guardrails,
      Integer dedupWindowSeconds) {
    requireAdmin(principal);
    String trimmed = requireName(name);
    if (store.findByNameIgnoreCase(trimmed).isPresent()) {
      throw new AppException("RULE_NAME_CONFLICT", "Rule name already exists", 409);
    }
    TriggerDefinition trigger = requireTrigger(triggerId);
    List<ConditionSpec> conds = conditions == null ? List.of() : conditions;
    List<ActionSpec> acts = actionSpecs == null ? List.of() : actionSpecs;
    validateConditions(trigger, conds);
    validateActions(acts);
    Instant now = clock.instant();
    UUID id = Ids.newId();
    AutomationRule rule =
        new AutomationRule(
            id,
            trimmed,
            description,
            trigger.triggerId(),
            trigger.category(),
            triggerParams == null ? Map.of() : triggerParams,
            conds,
            acts,
            guardrails == null ? Guardrails.NONE : guardrails,
            RuleStatus.INACTIVE,
            0,
            null,
            false,
            dedupWindowSeconds == null ? 300 : dedupWindowSeconds,
            principal.subject(),
            now,
            now);
    store.insert(rule);
    audit.log("CREATE", id, principal.subject(), Map.of("name", trimmed));
    cache.forceRefresh();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rule_id", id);
    data.put("name", trimmed);
    data.put("status", RuleStatus.INACTIVE.name());
    data.put("created_by", principal.subject());
    data.put("created_at", now.toString());
    return data;
  }

  public Map<String, Object> get(MedmatePrincipal principal, UUID id) {
    requireAdmin(principal);
    AutomationRule rule = requireRule(id);
    Map<String, Object> data = detail(rule);
    data.put("recent_fires", List.of());
    return data;
  }

  @Transactional
  public Map<String, Object> patch(
      MedmatePrincipal principal,
      UUID id,
      String name,
      String description,
      String triggerId,
      Map<String, Object> triggerParams,
      List<ConditionSpec> conditions,
      List<ActionSpec> actionSpecs,
      Guardrails guardrails,
      Integer dedupWindowSeconds) {
    requireAdmin(principal);
    AutomationRule existing = requireRule(id);
    String newName = name == null ? existing.name() : requireName(name);
    if (!newName.equalsIgnoreCase(existing.name())
        && store.findByNameIgnoreCase(newName).isPresent()) {
      throw new AppException("RULE_NAME_CONFLICT", "Rule name already exists", 409);
    }
    String newTriggerId = triggerId == null ? existing.triggerId() : triggerId;
    TriggerDefinition trigger = requireTrigger(newTriggerId);
    List<ConditionSpec> conds = conditions == null ? existing.conditions() : conditions;
    if (conditions != null && !conditions.isEmpty()) {
      rejectScheduleXConditions(existing.id());
    }
    List<ActionSpec> acts = actionSpecs == null ? existing.actions() : actionSpecs;
    validateConditions(trigger, conds);
    validateActions(acts);
    Guardrails gr = guardrails == null ? existing.guardrails() : guardrails;
    int dedup = dedupWindowSeconds == null ? existing.dedupWindowSeconds() : dedupWindowSeconds;
    RuleStatus status = existing.status();
    String resetReason = null;
    if (status == RuleStatus.ACTIVE) {
      status = RuleStatus.INACTIVE;
      resetReason = "RULE_EDITED";
    }
    Instant now = clock.instant();
    AutomationRule updated =
        new AutomationRule(
            existing.id(),
            newName,
            description == null ? existing.description() : description,
            trigger.triggerId(),
            trigger.category(),
            triggerParams == null ? existing.triggerParams() : triggerParams,
            conds,
            acts,
            gr,
            status,
            existing.fireCount(),
            existing.lastFiredAt(),
            existing.seedRule(),
            dedup,
            existing.createdBy(),
            existing.createdAt(),
            now);
    store.update(updated);
    audit.log("UPDATE", id, principal.subject(), Map.of("name", updated.name()));
    cache.forceRefresh();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rule_id", id);
    data.put("status", status.name());
    if (resetReason != null) {
      data.put("status_reset_reason", resetReason);
    }
    data.put("updated_at", now.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> setStatus(MedmatePrincipal principal, UUID id, String statusRaw) {
    requireAdmin(principal);
    RuleStatus next;
    try {
      next = RuleStatus.parse(statusRaw);
    } catch (RuntimeException ex) {
      throw new AppException("VALIDATION_ERROR", "Invalid status", 422);
    }
    if (next == null) {
      throw new AppException("VALIDATION_ERROR", "status is required", 422);
    }
    if (next == RuleStatus.ACTIVE && principal.role() != AuthRole.ADMIN_SUPER) {
      throw new AppException("FORBIDDEN", "Only admin_super can activate rules", 403);
    }
    AutomationRule existing = requireRule(id);
    if (next == RuleStatus.ACTIVE
        && existing.status() != RuleStatus.ACTIVE
        && store.countByStatus(RuleStatus.ACTIVE) >= MAX_ACTIVE_RULES) {
      throw new AppException(
          "ACTIVE_RULE_LIMIT_REACHED", "Maximum of 200 active rules reached", 422);
    }
    Instant now = clock.instant();
    RuleStatus previous = existing.status();
    AutomationRule updated =
        new AutomationRule(
            existing.id(),
            existing.name(),
            existing.description(),
            existing.triggerId(),
            existing.triggerCategory(),
            existing.triggerParams(),
            existing.conditions(),
            existing.actions(),
            existing.guardrails(),
            next,
            existing.fireCount(),
            existing.lastFiredAt(),
            existing.seedRule(),
            existing.dedupWindowSeconds(),
            existing.createdBy(),
            existing.createdAt(),
            now);
    store.update(updated);
    if (next == RuleStatus.SIMULATING) {
      store.markSimulatingStarted(id, now);
    } else if (previous == RuleStatus.SIMULATING) {
      store.clearSimulatingStarted(id);
    }
    audit.log(
        "STATUS",
        id,
        principal.subject(),
        Map.of("previous", previous.name(), "next", next.name()));
    cache.forceRefresh();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rule_id", id);
    data.put("previous_status", previous.name());
    data.put("new_status", next.name());
    data.put("updated_by", principal.subject());
    data.put("updated_at", now.toString());
    return data;
  }

  /** AC-008: called by job when SIMULATING exceeds 24h. */
  @Transactional
  public void autoRevertSimulating(UUID id) {
    AutomationRule existing = store.findById(id).orElse(null);
    if (existing == null || existing.status() != RuleStatus.SIMULATING) {
      return;
    }
    Instant now = clock.instant();
    AutomationRule updated =
        new AutomationRule(
            existing.id(),
            existing.name(),
            existing.description(),
            existing.triggerId(),
            existing.triggerCategory(),
            existing.triggerParams(),
            existing.conditions(),
            existing.actions(),
            existing.guardrails(),
            RuleStatus.INACTIVE,
            existing.fireCount(),
            existing.lastFiredAt(),
            existing.seedRule(),
            existing.dedupWindowSeconds(),
            existing.createdBy(),
            existing.createdAt(),
            now);
    store.update(updated);
    store.clearSimulatingStarted(id);
    audit.log(
        "STATUS",
        id,
        null,
        Map.of(
            "previous",
            RuleStatus.SIMULATING.name(),
            "next",
            RuleStatus.INACTIVE.name(),
            "reason",
            "SIMULATING_24H_CAP"));
    cache.forceRefresh();
  }

  @Transactional
  public Map<String, Object> delete(MedmatePrincipal principal, UUID id, boolean force) {
    requireAdmin(principal);
    AutomationRule existing = requireRule(id);
    if (existing.seedRule()) {
      throw new AppException("FORBIDDEN", "Seed rules cannot be deleted", 403);
    }
    if (force && principal.role() != AuthRole.ADMIN_SUPER) {
      throw new AppException("FORBIDDEN", "Only admin_super can force-delete", 403);
    }
    if (existing.status() == RuleStatus.ACTIVE) {
      throw new AppException("RULE_IS_ACTIVE", "Deactivate the rule before deleting", 422);
    }
    if (!force && existing.fireCount() > 0) {
      throw new AppException("RULE_HAS_FIRE_HISTORY", "Rule has fire history; use force=true", 422);
    }
    Instant now = clock.instant();
    store.softDelete(id, now);
    audit.log("DELETE", id, principal.subject(), Map.of("force", force));
    cache.forceRefresh();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("deleted", true);
    data.put("rule_id", id);
    data.put("deleted_at", now.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> duplicate(MedmatePrincipal principal, UUID id) {
    requireAdmin(principal);
    AutomationRule existing = requireRule(id);
    String copyName = existing.name() + " (Copy)";
    if (store.findByNameIgnoreCase(copyName).isPresent()) {
      throw new AppException("RULE_NAME_CONFLICT", "Rule name already exists", 409);
    }
    Instant now = clock.instant();
    UUID newId = Ids.newId();
    AutomationRule copy =
        new AutomationRule(
            newId,
            copyName,
            existing.description(),
            existing.triggerId(),
            existing.triggerCategory(),
            existing.triggerParams(),
            existing.conditions(),
            existing.actions(),
            existing.guardrails(),
            RuleStatus.INACTIVE,
            0,
            null,
            false,
            existing.dedupWindowSeconds(),
            principal.subject(),
            now,
            now);
    store.insert(copy);
    audit.log("DUPLICATE", newId, principal.subject(), Map.of("source_rule_id", id.toString()));
    cache.forceRefresh();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("new_rule_id", newId);
    data.put("name", copyName);
    data.put("status", RuleStatus.INACTIVE.name());
    data.put("created_at", now.toString());
    return data;
  }

  private void rejectScheduleXConditions(UUID ruleId) {
    if (seeds == null) {
      return;
    }
    Optional<SeedCatalogEntry> found = seeds.findByRuleId(ruleId);
    if (found == null || found.isEmpty()) {
      return;
    }
    if (SeedDefinitions.AUTO_SCHEDULE_X.equals(found.get().seedRuleKey())) {
      throw new AppException(
          "VALIDATION_ERROR",
          "AUTO_FLAG_SCHEDULE_X must always fire; conditions cannot be added",
          422);
    }
  }

  private AutomationRule requireRule(UUID id) {
    return store
        .findById(id)
        .orElseThrow(() -> new AppException("NOT_FOUND", "Rule not found", 404));
  }

  private TriggerDefinition requireTrigger(String triggerId) {
    return triggers
        .findById(triggerId)
        .filter(TriggerDefinition::active)
        .orElseThrow(() -> new AppException("INVALID_TRIGGER", "trigger_id not in registry", 422));
  }

  private void validateActions(List<ActionSpec> specs) {
    for (ActionSpec spec : specs) {
      if (spec == null) {
        throw new AppException("INVALID_ACTION", "action_id is required", 422);
      }
      if (spec.actionId() == null || spec.actionId().isBlank()) {
        throw new AppException("INVALID_ACTION", "action_id is required", 422);
      }
      if (actions.findById(spec.actionId()).isEmpty()) {
        throw new AppException("INVALID_ACTION", "action_id not in registry", 422);
      }
    }
  }

  private void validateConditions(TriggerDefinition trigger, List<ConditionSpec> conditions) {
    Set<String> allowed =
        trigger.availableConditions().stream()
            .map(s -> s.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
    for (ConditionSpec c : conditions) {
      if (c == null) {
        throw new AppException("INVALID_CONDITION_OPERATOR", "Condition operator is required", 422);
      }
      if (c.operator() == null || c.operator().isBlank()) {
        throw new AppException("INVALID_CONDITION_OPERATOR", "Condition operator is required", 422);
      }
      String op = c.operator().trim().toLowerCase(Locale.ROOT);
      if (!GLOBAL_OPERATORS.contains(op) && !allowed.contains(op)) {
        throw new AppException(
            "INVALID_CONDITION_OPERATOR", "Operator not supported for trigger", 422);
      }
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

  private static String requireName(String name) {
    if (name == null || name.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "name is required", 422);
    }
    return name.trim();
  }

  private static void requireAdmin(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (principal.role() != AuthRole.ADMIN_SUPER && principal.role() != AuthRole.ADMIN_OPERATIONS) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
  }

  private static Map<String, Object> listItem(AutomationRule r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("rule_id", r.id());
    m.put("name", r.name());
    m.put("trigger_id", r.triggerId());
    m.put("trigger_category", r.triggerCategory());
    m.put("conditions_summary", summarizeConditions(r.conditions()));
    m.put("actions_summary", summarizeActions(r.actions()));
    m.put("status", r.status().name());
    m.put("fire_count", r.fireCount());
    m.put("last_fired_at", r.lastFiredAt() == null ? null : r.lastFiredAt().toString());
    m.put("created_at", r.createdAt().toString());
    m.put("is_seed_rule", r.seedRule());
    return m;
  }

  private static Map<String, Object> detail(AutomationRule r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("rule_id", r.id());
    m.put("name", r.name());
    m.put("description", r.description());
    m.put("trigger_id", r.triggerId());
    m.put("trigger_params", r.triggerParams());
    m.put(
        "conditions",
        r.conditions().stream()
            .map(
                c -> {
                  Map<String, Object> row = new LinkedHashMap<>();
                  row.put("field", c.field());
                  row.put("operator", c.operator());
                  row.put("value", c.value());
                  return row;
                })
            .toList());
    m.put(
        "actions",
        r.actions().stream()
            .map(
                a -> {
                  Map<String, Object> row = new LinkedHashMap<>();
                  row.put("action_id", a.actionId());
                  row.put("params", a.params());
                  row.put("parallel", a.parallel());
                  return row;
                })
            .toList());
    m.put("guardrails", r.guardrails().toMap());
    m.put("status", r.status().name());
    m.put("fire_count", r.fireCount());
    m.put("last_fired_at", r.lastFiredAt() == null ? null : r.lastFiredAt().toString());
    m.put("is_seed_rule", r.seedRule());
    m.put("created_by", r.createdBy() == null ? "SYSTEM" : r.createdBy());
    m.put("created_at", r.createdAt().toString());
    return m;
  }

  private static String summarizeConditions(List<ConditionSpec> conditions) {
    return conditions.stream()
        .map(c -> c.field() + " " + c.operator() + " " + c.value())
        .collect(Collectors.joining(" AND "));
  }

  private static String summarizeActions(List<ActionSpec> actions) {
    return actions.stream().map(ActionSpec::actionId).collect(Collectors.joining(", "));
  }
}
