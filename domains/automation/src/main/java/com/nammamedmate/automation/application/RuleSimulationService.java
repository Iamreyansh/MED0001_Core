package com.nammamedmate.automation.application;

import com.nammamedmate.automation.application.port.out.ActionRegistryPort;
import com.nammamedmate.automation.application.port.out.ActivityLogPort;
import com.nammamedmate.automation.application.port.out.DedupPort;
import com.nammamedmate.automation.application.port.out.RateLimitPort;
import com.nammamedmate.automation.application.port.out.RuleStorePort;
import com.nammamedmate.automation.application.port.out.SimulationStorePort;
import com.nammamedmate.automation.application.port.out.TriggerEventQueryPort;
import com.nammamedmate.automation.application.port.out.TriggerEventQueryPort.TriggerEventRow;
import com.nammamedmate.automation.application.port.out.TriggerRegistryPort;
import com.nammamedmate.automation.domain.ActionDefinition;
import com.nammamedmate.automation.domain.ActionSpec;
import com.nammamedmate.automation.domain.AutomationRule;
import com.nammamedmate.automation.domain.AutomationSimulation;
import com.nammamedmate.automation.domain.ConditionEvaluator;
import com.nammamedmate.automation.domain.FalsePositiveRisk;
import com.nammamedmate.automation.domain.Guardrails;
import com.nammamedmate.automation.domain.RuleStatus;
import com.nammamedmate.automation.domain.SimulationRiskAssessor;
import com.nammamedmate.automation.domain.SimulationStatus;
import com.nammamedmate.automation.domain.TriggerDefinition;
import com.nammamedmate.automation.domain.TriggerEntityTypes;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RuleSimulationService {

  public static final int DEFAULT_SAMPLE_SIZE = 100;
  public static final int MAX_SAMPLE_SIZE = 1000;
  public static final Duration RESULT_RETENTION = Duration.ofDays(7);
  public static final Duration SIMULATING_CAP = Duration.ofHours(24);

  private final RuleStorePort rules;
  private final SimulationStorePort simulations;
  private final TriggerEventQueryPort events;
  private final TriggerRegistryPort triggers;
  private final ActionRegistryPort actions;
  private final ConditionEvaluator evaluator;
  private final DedupPort dedup;
  private final RateLimitPort rateLimit;
  private final ActivityLogPort activityLog;
  private final Clock clock;

  public RuleSimulationService(
      RuleStorePort rules,
      SimulationStorePort simulations,
      TriggerEventQueryPort events,
      TriggerRegistryPort triggers,
      ActionRegistryPort actions,
      ConditionEvaluator evaluator,
      DedupPort dedup,
      RateLimitPort rateLimit,
      ActivityLogPort activityLog,
      Clock clock) {
    this.rules = rules;
    this.simulations = simulations;
    this.events = events;
    this.triggers = triggers;
    this.actions = actions;
    this.evaluator = evaluator;
    this.dedup = dedup;
    this.rateLimit = rateLimit;
    this.activityLog = activityLog;
    this.clock = clock;
  }

  @Transactional
  public Map<String, Object> startBatch(
      MedmatePrincipal principal, UUID ruleId, Integer sampleSize, Boolean dryRun) {
    requireAdmin(principal);
    AutomationRule rule = requireRule(ruleId);
    if (rule.status() == RuleStatus.ACTIVE) {
      throw new AppException("RULE_IS_ACTIVE", "Cannot batch-simulate an ACTIVE rule", 422);
    }
    int size = sampleSize == null ? DEFAULT_SAMPLE_SIZE : sampleSize;
    if (size > MAX_SAMPLE_SIZE) {
      throw new AppException(
          "SAMPLE_SIZE_TOO_LARGE", "sample_size must be <= " + MAX_SAMPLE_SIZE, 400);
    }
    if (size < 1) {
      throw new AppException("VALIDATION_ERROR", "sample_size must be >= 1", 422);
    }
    // Batch simulation is always dry — dry_run flag accepted for API compat.
    if (dryRun != null && !dryRun) {
      throw new AppException("VALIDATION_ERROR", "dry_run must be true for batch simulate", 422);
    }
    Instant now = clock.instant();
    UUID simId = Ids.newId();
    AutomationSimulation row =
        new AutomationSimulation(
            simId,
            ruleId,
            size,
            0,
            0,
            0,
            null,
            null,
            null,
            List.of(),
            SimulationStatus.RUNNING,
            now,
            null,
            principal.subject(),
            null);
    simulations.insert(row);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("simulation_id", simId);
    data.put("rule_id", ruleId);
    data.put("status", SimulationStatus.RUNNING.name());
    data.put("sample_size", size);
    data.put("started_at", now.toString());
    data.put("estimated_completion_seconds", Math.max(1, size / 10));
    return data;
  }

  public Map<String, Object> getResults(
      MedmatePrincipal principal, UUID ruleId, UUID simulationId) {
    requireAdmin(principal);
    requireRule(ruleId);
    AutomationSimulation sim =
        simulations
            .findByRuleAndId(ruleId, simulationId)
            .orElseThrow(
                () -> new AppException("SIMULATION_NOT_FOUND", "Simulation not found", 404));
    // AC-004: return RUNNING body (200) while processing — not 404.
    // 425 SIMULATION_STILL_RUNNING available for clients that prefer wait semantics via query.
    return toResultBody(sim);
  }

  public Map<String, Object> preview(
      MedmatePrincipal principal, UUID ruleId, String entityType, UUID entityId) {
    requireAdmin(principal);
    AutomationRule rule = requireRule(ruleId);
    if (entityType == null || entityType.isBlank()) {
      throw new AppException("INVALID_ENTITY_TYPE", "entity_type is required", 422);
    }
    if (entityId == null) {
      throw new AppException("ENTITY_NOT_FOUND", "entity_id is required", 404);
    }
    String type = entityType.trim().toUpperCase(Locale.ROOT);
    TriggerDefinition trigger =
        triggers
            .findById(rule.triggerId())
            .orElseThrow(() -> new AppException("INVALID_TRIGGER", "trigger not found", 422));
    Set<String> allowed = TriggerEntityTypes.allowed(trigger);
    if (!allowed.contains(type)) {
      throw new AppException(
          "INVALID_ENTITY_TYPE", "entity_type not supported by this rule's trigger", 422);
    }
    TriggerEventRow event =
        events
            .findLatestByEntity(type, entityId)
            .orElseThrow(() -> new AppException("ENTITY_NOT_FOUND", "entity_id not found", 404));

    Map<String, Object> payload = new LinkedHashMap<>(event.payload());
    payload.putIfAbsent("entity_type", type);
    payload.putIfAbsent("entity_id", entityId.toString());
    ConditionEvaluator.EvalResult cond = evaluator.evaluate(rule.conditions(), payload);
    String entityName = resolveEntityName(payload, type, entityId);

    List<Map<String, Object>> actionRows = new ArrayList<>();
    if (cond.met()) {
      for (ActionSpec spec : rule.actions()) {
        ActionDefinition def = actions.findById(spec.actionId()).orElse(null);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("action", spec.actionId());
        row.put("params", spec.params());
        boolean requireApproval = false;
        boolean reversible = true;
        if (def != null) {
          requireApproval = def.alwaysRequireApproval();
          reversible = def.reversible();
        }
        row.put("would_require_approval", requireApproval);
        row.put("is_reversible", reversible);
        actionRows.add(row);
      }
    }

    Duration window = Duration.ofSeconds(rule.dedupWindowSeconds());
    boolean wouldDedup = dedup.isDuplicate(rule.id(), entityId, window);
    boolean wouldRate = false;
    Guardrails.RateLimit rl = rule.guardrails().rateLimit();
    if (rl != null) {
      wouldRate = rateLimit.wouldExceed(rule.id(), rl.maxFires(), rl.perMinutes());
    }

    boolean fires = cond.met();
    if (wouldDedup || wouldRate) {
      fires = false;
    }

    Instant now = clock.instant();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rule_id", ruleId);
    data.put("entity_type", type);
    data.put("entity_id", entityId);
    data.put("entity_name", entityName);
    data.put("conditions_evaluated", cond.evaluated());
    data.put("would_fire", fires);
    data.put("actions_that_would_fire", actionRows);
    data.put("would_be_rate_limited", wouldRate);
    data.put("would_be_deduplicated", wouldDedup);
    data.put("evaluated_at", now.toString());
    return data;
  }

  /** Worker: replay historical events for one RUNNING simulation. */
  @Transactional
  public void processSimulation(UUID simulationId) {
    AutomationSimulation sim = simulations.findById(simulationId).orElse(null);
    if (sim == null || sim.status() != SimulationStatus.RUNNING) {
      return;
    }
    AutomationRule rule = rules.findById(sim.ruleId()).orElse(null);
    if (rule == null) {
      simulations.markFailed(simulationId, clock.instant(), "Rule not found");
      return;
    }
    try {
      List<TriggerEventRow> sample = events.listRecentByTrigger(rule.triggerId(), sim.sampleSize());
      int scanned = sample.size();
      int failed = 0;
      Set<UUID> matchedEntities = new LinkedHashSet<>();
      List<Map<String, Object>> wouldFire = new ArrayList<>();

      for (TriggerEventRow event : sample) {
        Map<String, Object> payload = new LinkedHashMap<>(event.payload());
        ConditionEvaluator.EvalResult cond = evaluator.evaluate(rule.conditions(), payload);
        if (!cond.met()) {
          failed++;
          continue;
        }
        matchedEntities.add(event.entityId());
        String entityName = resolveEntityName(payload, event.entityType(), event.entityId());
        for (ActionSpec spec : rule.actions()) {
          ActionDefinition def = actions.findById(spec.actionId()).orElse(null);
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("action", spec.actionId());
          row.put("entity_id", event.entityId());
          row.put("entity_name", entityName);
          row.put("action_params", spec.params());
          boolean requireApproval = false;
          if (def != null) {
            requireApproval = def.alwaysRequireApproval();
          }
          row.put("would_require_approval", requireApproval);
          wouldFire.add(row);
          Map<String, Object> detail = new LinkedHashMap<>();
          detail.put("rule_id", rule.id().toString());
          detail.put("rule_name", rule.name());
          detail.put("entity_type", event.entityType());
          detail.put("entity_id", event.entityId().toString());
          detail.put("entity_name", entityName);
          detail.put("trigger_event_id", event.id().toString());
          detail.put("trigger_id", event.triggerId());
          detail.put("triggered_at", event.firedAt().toString());
          detail.put("params", spec.params());
          detail.put("prefix", "[SIMULATED]");
          activityLog.append(
              spec.actionId(), "SIMULATED", "[SIMULATED] would execute " + spec.actionId(), detail);
        }
      }

      TriggerDefinition trigger = triggers.findById(rule.triggerId()).orElse(null);
      Set<String> types = Set.of();
      if (trigger != null) {
        types = TriggerEntityTypes.allowed(trigger);
      }
      String label = TriggerEntityTypes.primaryLabel(types);
      SimulationRiskAssessor.Assessment assessment =
          SimulationRiskAssessor.assess(matchedEntities.size(), wouldFire, label, rule.actions());

      Instant now = clock.instant();
      simulations.markCompleted(
          simulationId,
          scanned,
          matchedEntities.size(),
          failed,
          assessment.risk(),
          assessment.riskDetails(),
          assessment.estimatedImpactSummary(),
          wouldFire,
          now,
          now.plus(RESULT_RETENTION));
    } catch (RuntimeException ex) {
      String msg = ex.getMessage();
      if (msg == null) {
        msg = ex.toString();
      }
      simulations.markFailed(simulationId, clock.instant(), msg);
    }
  }

  private Map<String, Object> toResultBody(AutomationSimulation sim) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("simulation_id", sim.id());
    data.put("rule_id", sim.ruleId());
    data.put("status", sim.status().name());
    data.put("sample_size", sim.sampleSize());
    if (sim.status() == SimulationStatus.RUNNING) {
      data.put("started_at", sim.startedAt().toString());
      return data;
    }
    data.put("events_scanned", sim.eventsScanned());
    data.put("entities_matched", sim.entitiesMatched());
    data.put("conditions_failed_count", sim.conditionsFailedCount());
    data.put("actions_that_would_fire", sim.actionsThatWouldFire());
    data.put(
        "false_positive_risk",
        sim.falsePositiveRisk() == null
            ? FalsePositiveRisk.LOW.name()
            : sim.falsePositiveRisk().name());
    data.put("risk_details", sim.riskDetails());
    data.put("estimated_impact_summary", sim.estimatedImpactSummary());
    data.put("completed_at", sim.completedAt() != null ? sim.completedAt().toString() : null);
    data.put("started_at", sim.startedAt().toString());
    return data;
  }

  private AutomationRule requireRule(UUID id) {
    return rules
        .findById(id)
        .orElseThrow(() -> new AppException("NOT_FOUND", "Rule not found", 404));
  }

  private static String resolveEntityName(
      Map<String, Object> payload, String entityType, UUID entityId) {
    Object name = payload.get("entity_name");
    if (name == null) {
      name = payload.get("name");
    }
    if (name == null) {
      name = payload.get("pharmacy_name");
    }
    if (name == null) {
      return entityType + "-" + entityId.toString().substring(0, 8);
    }
    return String.valueOf(name);
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
