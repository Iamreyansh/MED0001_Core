package com.nammamedmate.automation.application;

import com.nammamedmate.automation.application.port.out.ActionExecutorPort;
import com.nammamedmate.automation.application.port.out.ActionRegistryPort;
import com.nammamedmate.automation.application.port.out.ActivityLogPort;
import com.nammamedmate.automation.application.port.out.CircuitBreakerPort;
import com.nammamedmate.automation.application.port.out.DedupPort;
import com.nammamedmate.automation.application.port.out.KillSwitchPort;
import com.nammamedmate.automation.application.port.out.RateLimitPort;
import com.nammamedmate.automation.application.port.out.RuleLookupPort;
import com.nammamedmate.automation.application.port.out.RuleStorePort;
import com.nammamedmate.automation.application.port.out.TriggerEventStorePort;
import com.nammamedmate.automation.domain.ActionDefinition;
import com.nammamedmate.automation.domain.ActionSpec;
import com.nammamedmate.automation.domain.ApprovalCategory;
import com.nammamedmate.automation.domain.ApprovalRouter;
import com.nammamedmate.automation.domain.ApprovalUrgency;
import com.nammamedmate.automation.domain.ConditionEvaluator;
import com.nammamedmate.automation.domain.ConditionSpec;
import com.nammamedmate.automation.domain.Guardrails;
import com.nammamedmate.automation.domain.KillSwitchStatus;
import com.nammamedmate.automation.domain.RuleSnapshot;
import com.nammamedmate.automation.domain.RuleStatus;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RulesEngineService {

  private static final Logger log = LoggerFactory.getLogger(RulesEngineService.class);
  private static final String OUTCOME_KILL = "KILL_SWITCH_PAUSED";
  private static final String OUTCOME_DUP = "DUPLICATE_SKIPPED";
  private static final String OUTCOME_CONDITIONS_FAIL = "CONDITIONS_NOT_MET";
  private static final String OUTCOME_FIRED = "RULE_FIRED";
  private static final String OUTCOME_NO_RULE = "NO_RULE";
  private static final String OUTCOME_RATE_LIMITED = "RATE_LIMITED";
  private static final String OUTCOME_SIMULATED = "SIMULATED";
  static final String CIRCUIT_OPEN = "CIRCUIT_OPEN";

  private final KillSwitchPort killSwitch;
  private final TriggerEventStorePort events;
  private final RuleLookupPort rules;
  private final ActiveRuleCache cache;
  private final DedupPort dedup;
  private final ConditionEvaluator evaluator;
  private final ActionExecutorPort actions;
  private final ActivityLogPort activityLog;
  private final RateLimitPort rateLimit;
  private final RuleStorePort ruleStore;
  private final ApprovalQueueService approvals;
  private final ActionRegistryPort actionRegistry;
  private final CircuitBreakerPort circuits;
  private final Clock clock;
  private final Executor parallelExecutor = Executors.newVirtualThreadPerTaskExecutor();

  public RulesEngineService(
      KillSwitchPort killSwitch,
      TriggerEventStorePort events,
      RuleLookupPort rules,
      ActiveRuleCache cache,
      DedupPort dedup,
      ConditionEvaluator evaluator,
      ActionExecutorPort actions,
      ActivityLogPort activityLog,
      RateLimitPort rateLimit,
      RuleStorePort ruleStore,
      Clock clock) {
    this(
        killSwitch,
        events,
        rules,
        cache,
        dedup,
        evaluator,
        actions,
        activityLog,
        rateLimit,
        ruleStore,
        null,
        null,
        null,
        clock);
  }

  public RulesEngineService(
      KillSwitchPort killSwitch,
      TriggerEventStorePort events,
      RuleLookupPort rules,
      ActiveRuleCache cache,
      DedupPort dedup,
      ConditionEvaluator evaluator,
      ActionExecutorPort actions,
      ActivityLogPort activityLog,
      RateLimitPort rateLimit,
      RuleStorePort ruleStore,
      ApprovalQueueService approvals,
      ActionRegistryPort actionRegistry,
      Clock clock) {
    this(
        killSwitch,
        events,
        rules,
        cache,
        dedup,
        evaluator,
        actions,
        activityLog,
        rateLimit,
        ruleStore,
        approvals,
        actionRegistry,
        null,
        clock);
  }

  @Autowired
  public RulesEngineService(
      KillSwitchPort killSwitch,
      TriggerEventStorePort events,
      RuleLookupPort rules,
      ActiveRuleCache cache,
      DedupPort dedup,
      ConditionEvaluator evaluator,
      ActionExecutorPort actions,
      ActivityLogPort activityLog,
      RateLimitPort rateLimit,
      RuleStorePort ruleStore,
      ApprovalQueueService approvals,
      ActionRegistryPort actionRegistry,
      CircuitBreakerPort circuits,
      Clock clock) {
    this.killSwitch = killSwitch;
    this.events = events;
    this.rules = rules;
    this.cache = cache;
    this.dedup = dedup;
    this.evaluator = evaluator;
    this.actions = actions;
    this.activityLog = activityLog;
    this.rateLimit = rateLimit;
    this.ruleStore = ruleStore;
    this.approvals = approvals;
    this.actionRegistry = actionRegistry;
    this.circuits = circuits;
    this.clock = clock;
  }

  public Map<String, Object> evaluate(EvaluateCommand cmd) {
    Instant started = clock.instant();
    if (cmd == null || cmd.event() == null || cmd.event().triggerId() == null) {
      throw new AppException("INVALID_EVENT", "event.trigger_id is required", 400);
    }
    EventPayload event = cmd.event();
    Map<String, Object> payload =
        event.payload() == null ? Map.of() : new LinkedHashMap<>(event.payload());
    if (event.firedAt() != null) {
      payload.putIfAbsent("fired_at", event.firedAt().toString());
    }

    UUID entityId = event.entityId() == null ? Ids.newId() : event.entityId();
    UUID eventId =
        events.insert(
            event.triggerId(),
            event.entityType() == null ? "UNKNOWN" : event.entityType(),
            entityId,
            payload,
            event.firedAt() == null ? clock.instant() : event.firedAt());

    if (killSwitch.status() == KillSwitchStatus.PAUSED) {
      log.info("{} trigger_id={} event_id={}", OUTCOME_KILL, event.triggerId(), eventId);
      activityLog.append(
          "kill_switch",
          OUTCOME_KILL,
          "Kill switch paused",
          logDetail(cmd.ruleId(), event, eventId, entityId, Map.of()));
      events.markProcessed(eventId, clock.instant(), 0, 0, OUTCOME_KILL);
      return result(
          cmd.ruleId(), false, List.of(), List.of(), false, OUTCOME_KILL, millis(started));
    }

    RuleSnapshot rule = resolveRule(cmd);
    if (rule == null) {
      events.markProcessed(eventId, clock.instant(), 0, 0, OUTCOME_NO_RULE);
      return result(
          cmd.ruleId(), false, List.of(), List.of(), false, OUTCOME_NO_RULE, millis(started));
    }

    Duration window = Duration.ofSeconds(rule.dedupWindowSeconds());
    if (dedup.isDuplicate(rule.ruleId(), entityId, window)) {
      log.info("{} rule_id={} entity_id={}", OUTCOME_DUP, rule.ruleId(), entityId);
      activityLog.append(
          "dedup",
          OUTCOME_DUP,
          "Duplicate fire skipped",
          logDetail(rule.ruleId(), event, eventId, entityId, Map.of()));
      events.markProcessed(eventId, clock.instant(), 1, 0, OUTCOME_DUP);
      return result(rule.ruleId(), false, List.of(), List.of(), true, OUTCOME_DUP, millis(started));
    }

    ConditionEvaluator.EvalResult cond = evaluator.evaluate(rule.conditions(), payload);
    if (!cond.met()) {
      events.markProcessed(eventId, clock.instant(), 1, 0, OUTCOME_CONDITIONS_FAIL);
      return result(
          rule.ruleId(),
          false,
          cond.evaluated(),
          List.of(),
          false,
          OUTCOME_CONDITIONS_FAIL,
          millis(started));
    }

    Guardrails.RateLimit rl = rule.guardrails().rateLimit();
    if (rl != null
        && !cmd.dryRun()
        && !rateLimit.tryAcquire(rule.ruleId(), rl.maxFires(), rl.perMinutes())) {
      activityLog.append(
          "rate_limit",
          OUTCOME_RATE_LIMITED,
          "Rule fire rate limited",
          logDetail(rule.ruleId(), event, eventId, entityId, Map.of()));
      events.markProcessed(eventId, clock.instant(), 1, 0, OUTCOME_RATE_LIMITED);
      return result(
          rule.ruleId(),
          true,
          cond.evaluated(),
          List.of(),
          false,
          OUTCOME_RATE_LIMITED,
          millis(started));
    }

    boolean simulate = rule.status() == RuleStatus.SIMULATING;
    boolean dry = cmd.dryRun() || simulate;
    List<Map<String, Object>> dispatched =
        dry
            ? dryRunActions(rule, event, eventId, entityId, cond.evaluated(), simulate)
            : dispatchActions(rule, payload, event, eventId, entityId, cond.evaluated());

    if (!cmd.dryRun()) {
      dedup.recordFire(rule.ruleId(), entityId);
      if (ruleStore != null) {
        ruleStore.recordFire(rule.ruleId(), clock.instant());
      }
    }
    String outcome = simulate ? OUTCOME_SIMULATED : OUTCOME_FIRED;
    events.markProcessed(eventId, clock.instant(), 1, 1, outcome);
    return result(
        rule.ruleId(), true, cond.evaluated(), dispatched, false, outcome, millis(started));
  }

  private RuleSnapshot resolveRule(EvaluateCommand cmd) {
    if (cmd.ruleId() != null) {
      var cached = cache.findById(cmd.ruleId());
      if (cached.isPresent()) {
        return cached.get();
      }
      var looked = rules.findById(cmd.ruleId());
      if (looked.isPresent()) {
        return looked.get();
      }
    }
    if (cmd.conditions() != null || cmd.actions() != null) {
      UUID id = cmd.ruleId() == null ? Ids.newId() : cmd.ruleId();
      int dedupSecs =
          cmd.dedupWindowSeconds() == null || cmd.dedupWindowSeconds() <= 0
              ? 300
              : cmd.dedupWindowSeconds();
      return new RuleSnapshot(
          id,
          cmd.event().triggerId(),
          cmd.conditions() == null ? List.of() : cmd.conditions(),
          cmd.actions() == null ? List.of() : cmd.actions(),
          dedupSecs);
    }
    return null;
  }

  private List<Map<String, Object>> dryRunActions(
      RuleSnapshot rule,
      EventPayload event,
      UUID eventId,
      UUID entityId,
      List<Map<String, Object>> evaluated,
      boolean simulated) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (ActionSpec spec : rule.actions()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("action_id", spec.actionId());
      row.put("status", simulated ? "[SIMULATED]" : "DRY_RUN");
      UUID logId = null;
      if (simulated) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("params", spec.params());
        extra.put("prefix", "[SIMULATED]");
        extra.put("conditions_evaluated", evaluated);
        logId =
            activityLog.append(
                spec.actionId(),
                "SIMULATED",
                "[SIMULATED] would execute " + spec.actionId(),
                logDetail(rule.ruleId(), event, eventId, entityId, extra));
      }
      row.put("activity_log_id", logId);
      out.add(row);
    }
    return out;
  }

  private List<Map<String, Object>> dispatchActions(
      RuleSnapshot rule,
      Map<String, Object> payload,
      EventPayload event,
      UUID eventId,
      UUID entityId,
      List<Map<String, Object>> evaluated) {
    List<Map<String, Object>> out = new ArrayList<>();
    List<CompletableFuture<Map<String, Object>>> parallel = new ArrayList<>();
    Map<String, Object> ctx = new LinkedHashMap<>(payload);
    ctx.put("entity_type", event.entityType());
    ctx.put("entity_id", event.entityId());
    ctx.put("trigger_id", event.triggerId());
    ctx.put("rule_id", rule.ruleId());
    ctx.put("trigger_event_id", eventId);
    ctx.put("conditions_evaluated", evaluated);
    ctx.put("triggered_at", clock.instant().toString());

    for (ActionSpec spec : rule.actions()) {
      if (spec.parallel()) {
        parallel.add(
            CompletableFuture.supplyAsync(() -> runOne(spec, ctx, rule), parallelExecutor));
      } else {
        flush(parallel, out);
        out.add(runOne(spec, ctx, rule));
      }
    }
    flush(parallel, out);
    return out;
  }

  private void flush(
      List<CompletableFuture<Map<String, Object>>> parallel, List<Map<String, Object>> out) {
    if (parallel.isEmpty()) {
      return;
    }
    for (CompletableFuture<Map<String, Object>> f : parallel) {
      out.add(f.join());
    }
    parallel.clear();
  }

  private Map<String, Object> runOne(ActionSpec spec, Map<String, Object> ctx, RuleSnapshot rule) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("action_id", spec.actionId());
    try {
      if (circuits != null && !circuits.tryAcquire(spec.actionId())) {
        Map<String, Object> detail = new LinkedHashMap<>(ctx);
        detail.put("error", CIRCUIT_OPEN);
        UUID logId = activityLog.append(spec.actionId(), "EXCEPTION", CIRCUIT_OPEN, detail);
        row.put("status", "FAILED");
        row.put("activity_log_id", logId);
        return row;
      }
      if (approvals != null && requiresApproval(spec, ctx, rule)) {
        UUID approvalId = enqueueApproval(spec, ctx, rule);
        row.put("status", "PENDING_APPROVAL");
        row.put("approval_id", approvalId);
        row.put("activity_log_id", null);
        return row;
      }
      UUID activityId = actions.execute(spec.actionId(), spec.params(), ctx);
      row.put("status", "DISPATCHED");
      row.put("activity_log_id", activityId);
    } catch (RuntimeException ex) {
      log.warn("Action {} failed: {}", spec.actionId(), ex.toString());
      UUID logId =
          activityLog.append(
              spec.actionId(),
              "EXCEPTION",
              ex.getMessage() == null ? ex.toString() : ex.getMessage(),
              Map.of(
                  "error", ex.toString(), "entity_type", String.valueOf(ctx.get("entity_type"))));
      row.put("status", "FAILED");
      row.put("activity_log_id", logId);
    }
    return row;
  }

  private boolean requiresApproval(ActionSpec spec, Map<String, Object> ctx, RuleSnapshot rule) {
    ActionDefinition def =
        actionRegistry == null ? null : actionRegistry.findById(spec.actionId()).orElse(null);
    return ApprovalRouter.requiresApproval(
        spec.actionId(), spec.params(), ctx, rule.guardrails(), def);
  }

  private UUID enqueueApproval(ActionSpec spec, Map<String, Object> ctx, RuleSnapshot rule) {
    ActionDefinition def =
        actionRegistry == null ? null : actionRegistry.findById(spec.actionId()).orElse(null);
    Long amount = ApprovalRouter.extractAmount(spec.params(), ctx);
    ApprovalUrgency urgency = ApprovalRouter.urgency(amount, ctx);
    ApprovalCategory category = ApprovalRouter.category(spec.actionId(), def);
    String why =
        ApprovalRouter.why(spec.actionId(), amount, spec.params(), ctx, rule.guardrails(), def);
    String entityType = String.valueOf(ctx.getOrDefault("entity_type", "UNKNOWN"));
    UUID entityId = ApprovalRouter.parseUuid(ctx.get("entity_id"));
    String entityName = ApprovalRouter.stringVal(ctx.get("entity_name"));
    if (entityName == null) {
      entityName = ApprovalRouter.stringVal(spec.params().get("entity_name"));
    }
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> evaluated =
        (List<Map<String, Object>>) ctx.getOrDefault("conditions_evaluated", List.of());
    String ruleName = "";
    if (ruleStore != null) {
      var stored = ruleStore.findById(rule.ruleId());
      if (stored.isPresent()) {
        ruleName = stored.get().name();
      }
    }
    Map<String, Object> triggerCtx = new LinkedHashMap<>(ctx);
    triggerCtx.remove("conditions_evaluated");
    return approvals.enqueue(
        new ApprovalQueueService.EnqueueCommand(
            rule.ruleId(),
            ruleName,
            ApprovalRouter.parseUuid(ctx.get("trigger_event_id")),
            ApprovalRouter.stringVal(ctx.get("trigger_id")),
            spec.actionId(),
            spec.params(),
            entityType,
            entityId,
            entityName,
            amount,
            category,
            urgency,
            why,
            triggerCtx,
            evaluated,
            ApprovalRouter.estimatedImpact(spec.actionId(), entityType, entityName, amount),
            rule.guardrails().onRejectAction(),
            null));
  }

  private Map<String, Object> logDetail(
      UUID ruleId, EventPayload event, UUID eventId, UUID entityId, Map<String, Object> extra) {
    Map<String, Object> d = new LinkedHashMap<>();
    if (ruleId != null) {
      d.put("rule_id", ruleId.toString());
    }
    d.put("entity_type", event.entityType() == null ? "UNKNOWN" : event.entityType());
    d.put("trigger_id", event.triggerId());
    if (event.firedAt() != null) {
      d.put("triggered_at", event.firedAt().toString());
    }
    d.put("entity_id", entityId.toString());
    d.put("trigger_event_id", eventId.toString());
    d.putAll(extra);
    return d;
  }

  private long millis(Instant started) {
    return Math.max(0, Duration.between(started, clock.instant()).toMillis());
  }

  private Map<String, Object> result(
      UUID ruleId,
      boolean conditionsMet,
      List<Map<String, Object>> conditionsEvaluated,
      List<Map<String, Object>> actionsDispatched,
      boolean duplicateSkipped,
      String outcome,
      long evaluationMs) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rule_id", ruleId);
    data.put("conditions_met", conditionsMet);
    data.put("conditions_evaluated", conditionsEvaluated);
    data.put("actions_dispatched", actionsDispatched);
    data.put("duplicate_skipped", duplicateSkipped);
    data.put("outcome", outcome);
    data.put("evaluation_ms", evaluationMs);
    return data;
  }

  public record EventPayload(
      String triggerId,
      String entityType,
      UUID entityId,
      Map<String, Object> payload,
      Instant firedAt) {
    public EventPayload {
      payload = payload == null ? null : Map.copyOf(payload);
    }
  }

  public record EvaluateCommand(
      UUID ruleId,
      EventPayload event,
      boolean dryRun,
      List<ConditionSpec> conditions,
      List<ActionSpec> actions,
      Integer dedupWindowSeconds) {
    public EvaluateCommand {
      conditions = conditions == null ? null : List.copyOf(conditions);
      actions = actions == null ? null : List.copyOf(actions);
    }
  }
}
