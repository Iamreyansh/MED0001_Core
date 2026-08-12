package com.nammamedmate.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.automation.application.SeedAutomationsService;
import com.nammamedmate.automation.application.port.out.RuleStorePort;
import com.nammamedmate.automation.application.port.out.SeedCatalogPort;
import com.nammamedmate.automation.application.port.out.SimulationStorePort;
import com.nammamedmate.automation.application.port.out.TriggerRegistryPort;
import com.nammamedmate.automation.application.port.out.WorkflowExecutionPort;
import com.nammamedmate.automation.application.port.out.WorkflowStorePort;
import com.nammamedmate.automation.domain.ActionSpec;
import com.nammamedmate.automation.domain.AutomationRule;
import com.nammamedmate.automation.domain.AutomationSimulation;
import com.nammamedmate.automation.domain.AutomationWorkflow;
import com.nammamedmate.automation.domain.FalsePositiveRisk;
import com.nammamedmate.automation.domain.Guardrails;
import com.nammamedmate.automation.domain.RuleStatus;
import com.nammamedmate.automation.domain.SeedCatalogEntry;
import com.nammamedmate.automation.domain.SeedDefinitions;
import com.nammamedmate.automation.domain.SimulationStatus;
import com.nammamedmate.automation.domain.WorkflowStatus;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoverageStory008Test {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

  @Test
  @SuppressWarnings("unchecked")
  void listNullRiskActiveWorkflowAndNullInit() {
    SeedCatalogPort catalog = mock(SeedCatalogPort.class);
    RuleStorePort rules = mock(RuleStorePort.class);
    WorkflowStorePort workflows = mock(WorkflowStorePort.class);
    WorkflowExecutionPort executions = mock(WorkflowExecutionPort.class);
    SimulationStorePort simulations = mock(SimulationStorePort.class);
    TriggerRegistryPort triggers = mock(TriggerRegistryPort.class);
    SeedAutomationsService service =
        new SeedAutomationsService(
            catalog,
            rules,
            workflows,
            executions,
            simulations,
            triggers,
            Clock.fixed(NOW, ZoneOffset.UTC));
    UUID ruleId = UUID.randomUUID();
    UUID wfId = UUID.randomUUID();
    when(catalog.listAll())
        .thenReturn(
            List.of(
                new SeedCatalogEntry(
                    SeedDefinitions.AUTO_ASSIGN, ruleId, null, 1, "i", "e", NOW.plusSeconds(60)),
                new SeedCatalogEntry(SeedDefinitions.AUTO_DUNNING, null, wfId, 3, "i", "e", NOW),
                new SeedCatalogEntry("GONE", null, null, 9, "i", "e", null)));
    when(rules.findById(ruleId))
        .thenReturn(
            Optional.of(
                new AutomationRule(
                    ruleId,
                    "n",
                    "d",
                    "order_unassigned",
                    "DISPATCH",
                    Map.of(),
                    List.of(),
                    List.of(new ActionSpec("auto_assign_rider", Map.of(), false)),
                    Guardrails.NONE,
                    RuleStatus.SIMULATING,
                    0,
                    null,
                    true,
                    300,
                    ADMIN,
                    NOW,
                    NOW)));
    when(simulations.findLatestCompletedByRuleId(ruleId))
        .thenReturn(
            Optional.of(
                new AutomationSimulation(
                    UUID.randomUUID(),
                    ruleId,
                    1,
                    1,
                    1,
                    0,
                    null,
                    null,
                    null,
                    List.of(),
                    SimulationStatus.COMPLETED,
                    NOW,
                    null,
                    ADMIN,
                    null)));
    when(workflows.findById(wfId))
        .thenReturn(
            Optional.of(
                new AutomationWorkflow(
                    wfId,
                    SeedDefinitions.WF_DUNNING,
                    "d",
                    "invoice_overdue",
                    List.of(),
                    WorkflowStatus.ACTIVE,
                    1,
                    true,
                    ADMIN,
                    NOW,
                    NOW)));
    when(workflows.listAll()).thenReturn(List.of());
    when(simulations.findLatestCompletedByRuleId(any())).thenReturn(Optional.empty());
    when(simulations.findLatestCompletedByRuleId(ruleId))
        .thenReturn(
            Optional.of(
                new AutomationSimulation(
                    UUID.randomUUID(),
                    ruleId,
                    1,
                    1,
                    1,
                    0,
                    null,
                    null,
                    null,
                    List.of(),
                    SimulationStatus.COMPLETED,
                    NOW,
                    null,
                    ADMIN,
                    null)));

    MedmatePrincipal admin =
        new MedmatePrincipal(ADMIN, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    Map<String, Object> data = service.list(admin);
    List<Map<String, Object>> seedRules = (List<Map<String, Object>>) data.get("seed_rules");
    assertThat(seedRules.get(0).get("simulation_risk")).isNull();
    assertThat(seedRules.get(0).get("last_simulated_at")).isNull();
    assertThat(seedRules.get(1).get("recommended_next_step")).isNull();
    assertThat(data.get("initialized_at")).isEqualTo(NOW.toString());
    assertThat(FalsePositiveRisk.LOW.name()).isEqualTo("LOW");
  }
}
