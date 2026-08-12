package com.nammamedmate.automation.application;

import com.nammamedmate.automation.application.port.out.RuleStorePort;
import com.nammamedmate.automation.application.port.out.SeedCatalogPort;
import com.nammamedmate.automation.application.port.out.SimulationStorePort;
import com.nammamedmate.automation.application.port.out.TriggerRegistryPort;
import com.nammamedmate.automation.application.port.out.WorkflowExecutionPort;
import com.nammamedmate.automation.application.port.out.WorkflowStorePort;
import com.nammamedmate.automation.domain.AutomationRule;
import com.nammamedmate.automation.domain.AutomationSimulation;
import com.nammamedmate.automation.domain.AutomationWorkflow;
import com.nammamedmate.automation.domain.RuleStatus;
import com.nammamedmate.automation.domain.SeedCatalogEntry;
import com.nammamedmate.automation.domain.SeedDefinitions;
import com.nammamedmate.automation.domain.SeedDefinitions.RuleSeed;
import com.nammamedmate.automation.domain.SeedDefinitions.WorkflowSeed;
import com.nammamedmate.automation.domain.TriggerDefinition;
import com.nammamedmate.automation.domain.WorkflowExecutionStatus;
import com.nammamedmate.automation.domain.WorkflowStatus;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SeedAutomationsService {

  private final SeedCatalogPort catalog;
  private final RuleStorePort rules;
  private final WorkflowStorePort workflows;
  private final WorkflowExecutionPort executions;
  private final SimulationStorePort simulations;
  private final TriggerRegistryPort triggers;
  private final Clock clock;

  public SeedAutomationsService(
      SeedCatalogPort catalog,
      RuleStorePort rules,
      WorkflowStorePort workflows,
      WorkflowExecutionPort executions,
      SimulationStorePort simulations,
      TriggerRegistryPort triggers,
      Clock clock) {
    this.catalog = catalog;
    this.rules = rules;
    this.workflows = workflows;
    this.executions = executions;
    this.simulations = simulations;
    this.triggers = triggers;
    this.clock = clock;
  }

  public Map<String, Object> list(MedmatePrincipal principal) {
    requireViewer(principal);
    List<Map<String, Object>> seedRules = new ArrayList<>();
    List<Map<String, Object>> seedWorkflows = new ArrayList<>();
    Instant initializedAt = null;
    for (SeedCatalogEntry entry : catalog.listAll()) {
      if (initializedAt == null
          || (entry.initializedAt() != null && entry.initializedAt().isBefore(initializedAt))) {
        initializedAt = entry.initializedAt();
      }
      seedRules.add(ruleRow(entry));
    }
    for (AutomationWorkflow wf : workflows.listAll()) {
      if (!wf.seedWorkflow()) {
        continue;
      }
      seedWorkflows.add(workflowRow(wf));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("seed_rules", seedRules);
    data.put("seed_workflows", seedWorkflows);
    data.put("initialized_at", initializedAt == null ? null : initializedAt.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> initialize(MedmatePrincipal principal) {
    if (principal != null) {
      requireSuper(principal);
    }
    Instant now = clock.instant();
    UUID actor = principal == null ? null : principal.subject();
    int created = 0;
    int already = 0;
    int wfCreated = 0;
    int wfExisted = 0;
    Map<String, UUID> workflowIds = new LinkedHashMap<>();
    for (WorkflowSeed spec : SeedDefinitions.workflowSeeds()) {
      Optional<AutomationWorkflow> existing = workflows.findByNameIgnoreCase(spec.key());
      if (existing.isPresent()) {
        wfExisted++;
        workflowIds.put(spec.key(), existing.get().id());
        continue;
      }
      UUID id = Ids.newId();
      TriggerDefinition trigger = requireTrigger(spec.triggerId());
      AutomationWorkflow wf =
          new AutomationWorkflow(
              id,
              spec.key(),
              spec.description(),
              trigger.triggerId(),
              spec.steps(),
              WorkflowStatus.INACTIVE,
              1,
              true,
              actor,
              now,
              now);
      workflows.insert(wf);
      workflowIds.put(spec.key(), id);
      wfCreated++;
    }
    List<Map<String, Object>> ruleResults = new ArrayList<>();
    for (RuleSeed spec : SeedDefinitions.ruleSeeds()) {
      Optional<SeedCatalogEntry> existing = catalog.findByKey(spec.key());
      if (existing.isPresent()) {
        already++;
        ruleResults.add(ruleResult(spec.key(), existing.get().ruleId(), "ALREADY_EXISTS", null));
        continue;
      }
      UUID ruleId = insertRule(spec, actor, now);
      catalog.insert(
          new SeedCatalogEntry(
              spec.key(),
              ruleId,
              null,
              spec.displayOrder(),
              spec.expectedImpact(),
              spec.edgeCases(),
              now));
      created++;
      ruleResults.add(ruleResult(spec.key(), ruleId, "CREATED", null));
    }
    Optional<SeedCatalogEntry> dunningCat = catalog.findByKey(SeedDefinitions.AUTO_DUNNING);
    UUID dunningWf = workflowIds.get(SeedDefinitions.WF_DUNNING);
    if (dunningCat.isPresent()) {
      already++;
      ruleResults.add(
          ruleResult(
              SeedDefinitions.AUTO_DUNNING, null, "USES_WORKFLOW", dunningCat.get().workflowId()));
    } else {
      catalog.insert(
          new SeedCatalogEntry(
              SeedDefinitions.AUTO_DUNNING,
              null,
              dunningWf,
              3,
              SeedDefinitions.dunningImpact(),
              SeedDefinitions.dunningEdges(),
              now));
      created++;
      ruleResults.add(ruleResult(SeedDefinitions.AUTO_DUNNING, null, "USES_WORKFLOW", dunningWf));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("created", created);
    data.put("already_existed", already);
    data.put("workflows_created", wfCreated);
    data.put("workflows_already_existed", wfExisted);
    data.put("rules", ruleResults);
    data.put("initialized_at", now.toString());
    return data;
  }

  private UUID insertRule(RuleSeed spec, UUID actor, Instant now) {
    TriggerDefinition trigger = requireTrigger(spec.triggerId());
    Optional<AutomationRule> byName = rules.findByNameIgnoreCase(spec.name());
    if (byName.isPresent()) {
      return byName.get().id();
    }
    UUID id = Ids.newId();
    AutomationRule rule =
        new AutomationRule(
            id,
            spec.name(),
            SeedDefinitions.description(spec.expectedImpact(), spec.edgeCases()),
            trigger.triggerId(),
            trigger.category(),
            spec.triggerParams(),
            spec.conditions(),
            spec.actions(),
            spec.guardrails(),
            RuleStatus.INACTIVE,
            0,
            null,
            true,
            300,
            actor,
            now,
            now);
    rules.insert(rule);
    return id;
  }

  private Map<String, Object> ruleRow(SeedCatalogEntry entry) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("rule_id", entry.ruleId());
    m.put("seed_rule_key", entry.seedRuleKey());
    if (entry.ruleId() != null) {
      AutomationRule rule = rules.findById(entry.ruleId()).orElse(null);
      if (rule != null) {
        boolean simulated = false;
        Instant lastSim = null;
        String risk = null;
        Optional<AutomationSimulation> sim =
            Optional.ofNullable(simulations.findLatestCompletedByRuleId(rule.id()))
                .orElseGet(Optional::empty);
        if (sim.isPresent()) {
          simulated = true;
          lastSim = sim.get().completedAt();
          if (sim.get().falsePositiveRisk() != null) {
            risk = sim.get().falsePositiveRisk().name();
          }
        }
        m.put("name", rule.name());
        m.put("status", rule.status().name());
        m.put("fire_count", rule.fireCount());
        m.put("simulation_run", simulated);
        m.put("last_simulated_at", lastSim == null ? null : lastSim.toString());
        m.put("simulation_risk", risk);
        m.put("recommended_next_step", recommended(rule.status(), simulated));
        return m;
      }
    }
    AutomationWorkflow wf =
        entry.workflowId() == null ? null : workflows.findById(entry.workflowId()).orElse(null);
    m.put("name", displayName(entry.seedRuleKey()));
    m.put("status", wf == null ? WorkflowStatus.INACTIVE.name() : wf.status().name());
    m.put("fire_count", 0);
    m.put("simulation_run", false);
    m.put("last_simulated_at", null);
    m.put("simulation_risk", null);
    m.put(
        "recommended_next_step",
        recommended(wf == null ? RuleStatus.INACTIVE : toRuleStatus(wf.status()), false));
    return m;
  }

  private Map<String, Object> workflowRow(AutomationWorkflow wf) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("workflow_id", wf.id());
    m.put("seed_workflow_key", wf.name());
    m.put("name", displayWorkflowName(wf.name()));
    m.put("status", wf.status().name());
    m.put(
        "active_executions",
        executions.countByWorkflowAndStatus(wf.id(), WorkflowExecutionStatus.RUNNING));
    return m;
  }

  private TriggerDefinition requireTrigger(String triggerId) {
    return triggers
        .findById(triggerId)
        .filter(TriggerDefinition::active)
        .orElseThrow(() -> new AppException("INVALID_TRIGGER", "trigger_id not in registry", 422));
  }

  private static Map<String, Object> ruleResult(
      String key, UUID ruleId, String result, UUID workflowId) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("seed_rule_key", key);
    m.put("rule_id", ruleId);
    m.put("result", result);
    if (workflowId != null) {
      m.put("workflow_id", workflowId);
    }
    return m;
  }

  private static String recommended(RuleStatus status, boolean simulated) {
    if (status == RuleStatus.ACTIVE) {
      return null;
    }
    if (!simulated) {
      return "RUN_SIMULATION";
    }
    return null;
  }

  private static RuleStatus toRuleStatus(WorkflowStatus status) {
    return status == WorkflowStatus.ACTIVE ? RuleStatus.ACTIVE : RuleStatus.INACTIVE;
  }

  private static String displayName(String key) {
    if (SeedDefinitions.AUTO_DUNNING.equals(key)) {
      return "Auto-Dunning Overdue Invoices";
    }
    return key;
  }

  private static String displayWorkflowName(String key) {
    return switch (key) {
      case SeedDefinitions.WF_DUNNING -> "Invoice Dunning Ladder";
      case SeedDefinitions.WF_ONBOARDING -> "Pharmacy Onboarding";
      case SeedDefinitions.WF_WIN_BACK -> "Win-Back";
      default -> key;
    };
  }

  private static void requireViewer(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (principal.role() != AuthRole.ADMIN_SUPER && principal.role() != AuthRole.ADMIN_OPERATIONS) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
  }

  private static void requireSuper(MedmatePrincipal principal) {
    if (principal.role() != AuthRole.ADMIN_SUPER) {
      throw new AppException("FORBIDDEN", "Only admin_super can initialize seed rules", 403);
    }
  }
}
