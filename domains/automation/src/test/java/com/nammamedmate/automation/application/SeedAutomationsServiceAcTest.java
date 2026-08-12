package com.nammamedmate.automation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.nammamedmate.automation.domain.ConditionSpec;
import com.nammamedmate.automation.domain.FalsePositiveRisk;
import com.nammamedmate.automation.domain.Guardrails;
import com.nammamedmate.automation.domain.RuleStatus;
import com.nammamedmate.automation.domain.SeedCatalogEntry;
import com.nammamedmate.automation.domain.SeedDefinitions;
import com.nammamedmate.automation.domain.SimulationStatus;
import com.nammamedmate.automation.domain.TriggerDefinition;
import com.nammamedmate.automation.domain.WorkflowExecutionStatus;
import com.nammamedmate.automation.domain.WorkflowStatus;
import com.nammamedmate.kernel.error.AppException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeedAutomationsServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

  @Mock SeedCatalogPort catalog;
  @Mock RuleStorePort rules;
  @Mock WorkflowStorePort workflows;
  @Mock WorkflowExecutionPort executions;
  @Mock SimulationStorePort simulations;
  @Mock TriggerRegistryPort triggers;

  private SeedAutomationsService service;
  private MedmatePrincipal superAdmin;
  private MedmatePrincipal opsAdmin;

  @BeforeEach
  void setUp() {
    service =
        new SeedAutomationsService(
            catalog,
            rules,
            workflows,
            executions,
            simulations,
            triggers,
            Clock.fixed(NOW, ZoneOffset.UTC));
    superAdmin = new MedmatePrincipal(ADMIN, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    opsAdmin = new MedmatePrincipal(ADMIN, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    when(triggers.findById(anyString()))
        .thenAnswer(
            inv -> {
              String id = inv.getArgument(0);
              return Optional.of(
                  new TriggerDefinition(
                      id, "FINANCE", "n", "d", List.of(), List.of(), List.of(), true));
            });
    when(workflows.findByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
    when(catalog.findByKey(anyString())).thenReturn(Optional.empty());
    when(rules.findByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac001_freshInitializeCreatesSixInactiveAndThreeWorkflows() {
    Map<String, Object> data = service.initialize(superAdmin);
    assertThat(data.get("created")).isEqualTo(6);
    assertThat(data.get("already_existed")).isEqualTo(0);
    assertThat(data.get("workflows_created")).isEqualTo(3);
    assertThat(data.get("workflows_already_existed")).isEqualTo(0);
    List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("rules");
    assertThat(results).hasSize(6);
    assertThat(results.stream().map(r -> r.get("result")))
        .containsExactlyInAnyOrder(
            "CREATED", "CREATED", "CREATED", "CREATED", "CREATED", "USES_WORKFLOW");

    ArgumentCaptor<AutomationRule> ruleCap = ArgumentCaptor.forClass(AutomationRule.class);
    verify(rules, org.mockito.Mockito.times(5)).insert(ruleCap.capture());
    assertThat(ruleCap.getAllValues())
        .allMatch(r -> r.status() == RuleStatus.INACTIVE && r.seedRule());

    ArgumentCaptor<AutomationWorkflow> wfCap = ArgumentCaptor.forClass(AutomationWorkflow.class);
    verify(workflows, org.mockito.Mockito.times(3)).insert(wfCap.capture());
    assertThat(wfCap.getAllValues())
        .allMatch(w -> w.status() == WorkflowStatus.INACTIVE && w.seedWorkflow());
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac002_secondInitializeDoesNotMutate() {
    UUID ruleId = UUID.randomUUID();
    UUID wfId = UUID.randomUUID();
    when(workflows.findByNameIgnoreCase(anyString()))
        .thenReturn(Optional.of(workflow(wfId, SeedDefinitions.WF_DUNNING)));
    when(catalog.findByKey(anyString()))
        .thenReturn(
            Optional.of(
                new SeedCatalogEntry(
                    "AUTO_ASSIGN_UNASSIGNED_ORDERS", ruleId, null, 1, "i", "e", NOW)));
    when(catalog.findByKey(SeedDefinitions.AUTO_DUNNING))
        .thenReturn(
            Optional.of(
                new SeedCatalogEntry(SeedDefinitions.AUTO_DUNNING, null, wfId, 3, "i", "e", NOW)));

    Map<String, Object> data = service.initialize(superAdmin);
    assertThat(data.get("created")).isEqualTo(0);
    assertThat(data.get("already_existed")).isEqualTo(6);
    assertThat(data.get("workflows_created")).isEqualTo(0);
    assertThat(data.get("workflows_already_existed")).isEqualTo(3);
    List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("rules");
    assertThat(results)
        .allMatch(
            r ->
                "ALREADY_EXISTS".equals(r.get("result"))
                    || "USES_WORKFLOW".equals(r.get("result")));
    verify(rules, never()).insert(any());
    verify(workflows, never()).insert(any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac003_listReturnsSimulationRiskOrNull() {
    UUID ruleId = UUID.randomUUID();
    UUID wfId = UUID.randomUUID();
    SeedCatalogEntry entry =
        new SeedCatalogEntry(SeedDefinitions.AUTO_ASSIGN, ruleId, null, 1, "i", "e", NOW);
    when(catalog.listAll()).thenReturn(List.of(entry));
    when(rules.findById(ruleId)).thenReturn(Optional.of(rule(ruleId, RuleStatus.INACTIVE, 0)));
    when(simulations.findLatestCompletedByRuleId(ruleId))
        .thenReturn(
            Optional.of(
                new AutomationSimulation(
                    UUID.randomUUID(),
                    ruleId,
                    10,
                    10,
                    2,
                    0,
                    FalsePositiveRisk.LOW,
                    "ok",
                    "sum",
                    List.of(),
                    SimulationStatus.COMPLETED,
                    NOW,
                    NOW,
                    ADMIN,
                    null)));
    when(workflows.listAll()).thenReturn(List.of(workflow(wfId, SeedDefinitions.WF_DUNNING)));
    when(executions.countByWorkflowAndStatus(wfId, WorkflowExecutionStatus.RUNNING)).thenReturn(2L);

    Map<String, Object> data = service.list(opsAdmin);
    List<Map<String, Object>> seedRules = (List<Map<String, Object>>) data.get("seed_rules");
    assertThat(seedRules.getFirst().get("simulation_risk")).isEqualTo("LOW");
    assertThat(seedRules.getFirst().get("simulation_run")).isEqualTo(true);
    assertThat(seedRules.getFirst().get("recommended_next_step")).isNull();

    when(simulations.findLatestCompletedByRuleId(ruleId)).thenReturn(Optional.empty());
    Map<String, Object> fresh = service.list(opsAdmin);
    List<Map<String, Object>> freshRules = (List<Map<String, Object>>) fresh.get("seed_rules");
    assertThat(freshRules.getFirst().get("simulation_risk")).isNull();
    assertThat(freshRules.getFirst().get("recommended_next_step")).isEqualTo("RUN_SIMULATION");
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac006_payoutSeedCapsFiveMillion() {
    service.initialize(null);
    ArgumentCaptor<AutomationRule> cap = ArgumentCaptor.forClass(AutomationRule.class);
    verify(rules, org.mockito.Mockito.times(5)).insert(cap.capture());
    AutomationRule payout =
        cap.getAllValues().stream()
            .filter(r -> r.name().startsWith("Auto-Release"))
            .findFirst()
            .orElseThrow();
    assertThat(payout.guardrails().valueCap()).isEqualTo(5_000_000L);
    assertThat(payout.guardrails().requireApprovalAbove()).isEqualTo(5_000_000L);
    assertThat(payout.conditions())
        .containsExactly(new ConditionSpec("payout.amount_paise", "lt", 5_000_000L));
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac008_recommendedNextStep() {
    UUID inactive = UUID.randomUUID();
    UUID active = UUID.randomUUID();
    when(catalog.listAll())
        .thenReturn(
            List.of(
                new SeedCatalogEntry(SeedDefinitions.AUTO_ASSIGN, inactive, null, 1, "i", "e", NOW),
                new SeedCatalogEntry(
                    SeedDefinitions.AUTO_SCHEDULE_X,
                    active,
                    null,
                    6,
                    "i",
                    "e",
                    NOW.plusSeconds(1))));
    when(rules.findById(inactive)).thenReturn(Optional.of(rule(inactive, RuleStatus.INACTIVE, 0)));
    when(rules.findById(active)).thenReturn(Optional.of(rule(active, RuleStatus.ACTIVE, 48)));
    when(simulations.findLatestCompletedByRuleId(inactive)).thenReturn(Optional.empty());
    when(simulations.findLatestCompletedByRuleId(active))
        .thenReturn(
            Optional.of(
                new AutomationSimulation(
                    UUID.randomUUID(),
                    active,
                    1,
                    1,
                    1,
                    0,
                    FalsePositiveRisk.LOW,
                    null,
                    null,
                    List.of(),
                    SimulationStatus.COMPLETED,
                    NOW,
                    NOW,
                    ADMIN,
                    null)));
    when(workflows.listAll()).thenReturn(List.of());

    Map<String, Object> data = service.list(superAdmin);
    List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("seed_rules");
    assertThat(rows.get(0).get("recommended_next_step")).isEqualTo("RUN_SIMULATION");
    assertThat(rows.get(1).get("recommended_next_step")).isNull();
    assertThat(rows.get(1).get("status")).isEqualTo("ACTIVE");
    assertThat(data.get("initialized_at")).isEqualTo(NOW.toString());
  }

  @Test
  void opsCannotInitialize() {
    assertThatThrownBy(() -> service.initialize(opsAdmin))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void listAuthBranches() {
    assertThatThrownBy(() -> service.list(null))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("UNAUTHORIZED"));
    MedmatePrincipal finance =
        new MedmatePrincipal(ADMIN, AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.list(finance))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("FORBIDDEN"));
    when(catalog.listAll()).thenReturn(List.of());
    when(workflows.listAll()).thenReturn(List.of());
    assertThat(service.list(superAdmin).get("initialized_at")).isNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  void listWorkflowBackedAndMissingRule() {
    UUID wfId = UUID.randomUUID();
    when(catalog.listAll())
        .thenReturn(
            List.of(
                new SeedCatalogEntry(SeedDefinitions.AUTO_DUNNING, null, wfId, 3, "i", "e", NOW),
                new SeedCatalogEntry("GONE", UUID.randomUUID(), null, 9, "i", "e", NOW)));
    when(workflows.findById(wfId))
        .thenReturn(Optional.of(workflow(wfId, SeedDefinitions.WF_DUNNING)));
    when(workflows.listAll())
        .thenReturn(
            List.of(
                workflow(wfId, SeedDefinitions.WF_DUNNING),
                new AutomationWorkflow(
                    UUID.randomUUID(),
                    "custom",
                    "d",
                    "invoice_overdue",
                    List.of(),
                    WorkflowStatus.INACTIVE,
                    1,
                    false,
                    ADMIN,
                    NOW,
                    NOW)));
    when(rules.findById(any())).thenReturn(Optional.empty());
    Map<String, Object> data = service.list(superAdmin);
    List<Map<String, Object>> seedRules = (List<Map<String, Object>>) data.get("seed_rules");
    assertThat(seedRules.getFirst().get("name")).isEqualTo("Auto-Dunning Overdue Invoices");
    assertThat(seedRules.getFirst().get("recommended_next_step")).isEqualTo("RUN_SIMULATION");
    List<Map<String, Object>> wfs = (List<Map<String, Object>>) data.get("seed_workflows");
    assertThat(wfs).hasSize(1);
    assertThat(wfs.getFirst().get("name")).isEqualTo("Invoice Dunning Ladder");
  }

  @Test
  void reuseExistingRuleNameAndMissingTrigger() {
    UUID existing = UUID.randomUUID();
    when(rules.findByNameIgnoreCase("Auto-Assign Unassigned Orders"))
        .thenReturn(Optional.of(rule(existing, RuleStatus.INACTIVE, 0)));
    Map<String, Object> data = service.initialize(superAdmin);
    assertThat(data.get("created")).isEqualTo(6);

    when(triggers.findById(anyString())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.initialize(superAdmin))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("INVALID_TRIGGER"));
  }

  @Test
  void displayNamesForOtherWorkflows() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    when(catalog.listAll()).thenReturn(List.of());
    when(workflows.listAll())
        .thenReturn(
            List.of(
                workflow(a, SeedDefinitions.WF_ONBOARDING),
                workflow(b, SeedDefinitions.WF_WIN_BACK),
                workflow(UUID.randomUUID(), "OTHER_SEED")));
    Map<String, Object> data = service.list(superAdmin);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> wfs = (List<Map<String, Object>>) data.get("seed_workflows");
    assertThat(wfs)
        .extracting(m -> m.get("name"))
        .contains("Pharmacy Onboarding", "Win-Back", "OTHER_SEED");
  }

  private static AutomationRule rule(UUID id, RuleStatus status, int fires) {
    return new AutomationRule(
        id,
        "Auto-Assign Unassigned Orders",
        "d",
        "order_unassigned",
        "DISPATCH",
        Map.of(),
        List.of(),
        List.of(new ActionSpec("auto_assign_rider", Map.of(), false)),
        Guardrails.NONE,
        status,
        fires,
        null,
        true,
        300,
        ADMIN,
        NOW,
        NOW);
  }

  private static AutomationWorkflow workflow(UUID id, String name) {
    return new AutomationWorkflow(
        id,
        name,
        "d",
        "invoice_overdue",
        List.of(),
        WorkflowStatus.INACTIVE,
        1,
        true,
        ADMIN,
        NOW,
        NOW);
  }
}
