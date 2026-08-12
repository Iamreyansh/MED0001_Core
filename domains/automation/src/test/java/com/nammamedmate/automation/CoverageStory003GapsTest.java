package com.nammamedmate.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.automation.adapter.in.web.AdminAutomationWorkflowsController;
import com.nammamedmate.automation.adapter.in.web.InternalRulesEvaluateController;
import com.nammamedmate.automation.adapter.out.persistence.JdbcWorkflowExecutionAdapter;
import com.nammamedmate.automation.adapter.out.persistence.JdbcWorkflowStoreAdapter;
import com.nammamedmate.automation.application.InternalAutomationAuth;
import com.nammamedmate.automation.application.RulesEngineService;
import com.nammamedmate.automation.application.WorkflowEngineService;
import com.nammamedmate.automation.application.WorkflowManagementService;
import com.nammamedmate.automation.application.port.out.ActionExecutorPort;
import com.nammamedmate.automation.application.port.out.ActionRegistryPort;
import com.nammamedmate.automation.application.port.out.ActivityLogPort;
import com.nammamedmate.automation.application.port.out.TriggerRegistryPort;
import com.nammamedmate.automation.application.port.out.WorkflowExecutionPort;
import com.nammamedmate.automation.application.port.out.WorkflowStorePort;
import com.nammamedmate.automation.domain.ActionDefinition;
import com.nammamedmate.automation.domain.AutomationWorkflow;
import com.nammamedmate.automation.domain.ConditionEvaluator;
import com.nammamedmate.automation.domain.ConditionSpec;
import com.nammamedmate.automation.domain.StepType;
import com.nammamedmate.automation.domain.TriggerDefinition;
import com.nammamedmate.automation.domain.WorkflowExecution;
import com.nammamedmate.automation.domain.WorkflowExecutionStatus;
import com.nammamedmate.automation.domain.WorkflowStatus;
import com.nammamedmate.automation.domain.WorkflowStep;
import com.nammamedmate.automation.domain.WorkflowStepValidator;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.lang.reflect.Constructor;
import java.sql.ResultSet;
import java.sql.Timestamp;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CoverageStory003GapsTest {

  private static final Instant NOW = Instant.parse("2026-07-24T09:00:00Z");
  private static final UUID WF = UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");
  private static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

  @Mock WorkflowStorePort store;
  @Mock WorkflowExecutionPort executions;
  @Mock TriggerRegistryPort triggers;
  @Mock ActionRegistryPort actions;
  @Mock ActionExecutorPort actionExecutor;
  @Mock ActivityLogPort activityLog;
  @Mock RulesEngineService rulesEngine;
  @Mock WorkflowManagementService management;
  @Mock JdbcTemplate jdbc;
  @Mock ResultSet rs;
  @Mock ObjectMapper boomMapper;

  private WorkflowManagementService mgmt;
  private WorkflowEngineService engine;
  private MedmatePrincipal superAdmin;
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  @BeforeEach
  void setUp() {
    mgmt = new WorkflowManagementService(store, executions, triggers, actions, clock);
    engine =
        new WorkflowEngineService(
            store, executions, actionExecutor, activityLog, new ConditionEvaluator(clock), clock);
    superAdmin = new MedmatePrincipal(ADMIN, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    when(triggers.findById("invoice_overdue"))
        .thenReturn(
            Optional.of(
                new TriggerDefinition(
                    "invoice_overdue",
                    "FINANCE",
                    "n",
                    "d",
                    List.of(),
                    List.of(),
                    List.of(),
                    true)));
    when(actions.findById("send_notification"))
        .thenReturn(
            Optional.of(
                new ActionDefinition(
                    "send_notification",
                    "NOTIFICATION",
                    "n",
                    "d",
                    List.of(),
                    List.of(),
                    false,
                    false,
                    null)));
    when(actionExecutor.execute(anyString(), anyMap(), anyMap())).thenReturn(UUID.randomUUID());
    when(activityLog.append(anyString(), anyString(), anyString(), anyMap()))
        .thenReturn(UUID.randomUUID());
  }

  @Test
  void managementEdgeBranches() {
    assertThatThrownBy(() -> mgmt.list(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(
            () ->
                mgmt.list(
                    new MedmatePrincipal(ADMIN, AuthRole.CUSTOMER, null, TokenScope.FULL, "j")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> mgmt.create(superAdmin, " ", "d", "invoice_overdue", List.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> mgmt.toggle(null, WF, "ACTIVE"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> mgmt.toggle(superAdmin, WF, "NOPE"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> mgmt.toggle(superAdmin, WF, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> mgmt.get(superAdmin, WF))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NOT_FOUND");

    AutomationWorkflow existing = sample(WorkflowStatus.ACTIVE);
    when(store.findById(WF)).thenReturn(Optional.of(existing));
    when(store.findByNameIgnoreCase("Taken")).thenReturn(Optional.of(existing));
    assertThatThrownBy(() -> mgmt.patch(superAdmin, WF, "Taken", null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("WORKFLOW_NAME_EXISTS");

    when(store.findByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
    when(executions.pauseRunning(WF)).thenReturn(0);
    when(triggers.findById("bad")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> mgmt.patch(superAdmin, WF, null, null, "bad", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_TRIGGER");

    assertThatThrownBy(() -> mgmt.listExecutions(superAdmin, WF, "NOPE", 0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    when(executions.count(WF, null)).thenReturn(0L);
    when(executions.list(eq(WF), eq(null), eq(0), eq(20))).thenReturn(List.of());
    assertThat(mgmt.listExecutions(superAdmin, WF, "  ", 0).data().get("executions"))
        .asList()
        .isEmpty();

    UUID exec = UUID.randomUUID();
    when(executions.findById(exec)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> mgmt.cancel(superAdmin, WF, exec))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NOT_FOUND");

    when(executions.findById(exec))
        .thenReturn(
            Optional.of(
                new WorkflowExecution(
                    exec,
                    UUID.randomUUID(),
                    1,
                    "PHARMACY",
                    UUID.randomUUID(),
                    "x",
                    "s1",
                    WorkflowExecutionStatus.RUNNING,
                    null,
                    Map.of(),
                    NOW,
                    null,
                    NOW,
                    List.of())));
    assertThatThrownBy(() -> mgmt.cancel(superAdmin, WF, exec))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NOT_FOUND");

    when(executions.findById(exec))
        .thenReturn(
            Optional.of(
                new WorkflowExecution(
                    exec,
                    WF,
                    1,
                    "PHARMACY",
                    UUID.randomUUID(),
                    "x",
                    "s1",
                    WorkflowExecutionStatus.COMPLETED,
                    null,
                    Map.of(),
                    NOW,
                    NOW,
                    NOW,
                    List.of())));
    assertThatThrownBy(() -> mgmt.cancel(superAdmin, WF, exec))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    when(actions.findById("missing")).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                mgmt.create(
                    superAdmin,
                    "BadAction",
                    "d",
                    "invoice_overdue",
                    List.of(
                        new WorkflowStep(
                            "s1", StepType.ACTION, "missing", Map.of(), null, null, null, null))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_ACTION");

    when(executions.avgCompletionHours(WF)).thenReturn(null);
    when(executions.countByWorkflowAndStatus(any(), any())).thenReturn(0L);
    assertThat(mgmt.get(superAdmin, WF).get("stats")).isInstanceOf(Map.class);

    when(executions.count(WF, WorkflowExecutionStatus.RUNNING)).thenReturn(0L);
    when(executions.list(eq(WF), eq(WorkflowExecutionStatus.RUNNING), eq(0), eq(20)))
        .thenReturn(List.of());
    assertThat(mgmt.listExecutions(superAdmin, WF, "RUNNING", null).meta().page()).isEqualTo(1);
  }

  @Test
  void engineEdgeBranches() throws Exception {
    assertThat(engine.onTrigger(null, "PHARMACY", UUID.randomUUID(), null, Map.of())).isEmpty();
    assertThat(engine.onTrigger("invoice_overdue", "PHARMACY", null, null, Map.of())).isEmpty();
    assertThat(engine.start(null, "PHARMACY", UUID.randomUUID(), null, Map.of())).isEmpty();
    assertThat(engine.start(sample(WorkflowStatus.ACTIVE), "PHARMACY", null, null, Map.of()))
        .isEmpty();

    AutomationWorkflow emptySteps =
        new AutomationWorkflow(
            WF,
            "e",
            "d",
            "invoice_overdue",
            List.of(),
            WorkflowStatus.ACTIVE,
            1,
            false,
            null,
            NOW,
            NOW);
    assertThat(engine.start(emptySteps, null, UUID.randomUUID(), null, null)).isEmpty();

    when(executions.findRunning(any(), any())).thenReturn(Optional.empty());
    org.mockito.Mockito.doNothing().when(executions).insert(any());
    org.mockito.Mockito.doNothing().when(executions).update(any());

    AutomationWorkflow orphanCurrent =
        new AutomationWorkflow(
            WF,
            "e",
            "d",
            "invoice_overdue",
            List.of(
                new WorkflowStep(
                    "s1",
                    StepType.ACTION,
                    "send_notification",
                    Map.of(),
                    null,
                    null,
                    "gone",
                    null)),
            WorkflowStatus.ACTIVE,
            1,
            false,
            null,
            NOW,
            NOW);
    // Force current step to missing id by starting then manually advancing via processDueWaits
    // with a WAIT that points nowhere + null workflow / bad step.
    when(store.findById(WF)).thenReturn(Optional.empty());
    WorkflowExecution due =
        new WorkflowExecution(
            UUID.randomUUID(),
            WF,
            1,
            "PHARMACY",
            UUID.randomUUID(),
            "n",
            "s1",
            WorkflowExecutionStatus.RUNNING,
            NOW.minusSeconds(1),
            Map.of(),
            NOW,
            null,
            NOW,
            List.of());
    when(executions.listWaitDue(any(), eq(50))).thenReturn(List.of(due));
    assertThat(engine.processDueWaits(50)).isEqualTo(0);

    when(store.findById(WF)).thenReturn(Optional.of(sample(WorkflowStatus.ACTIVE)));
    WorkflowExecution notWait =
        new WorkflowExecution(
            UUID.randomUUID(),
            WF,
            1,
            "PHARMACY",
            UUID.randomUUID(),
            "n",
            "s1",
            WorkflowExecutionStatus.RUNNING,
            NOW.minusSeconds(1),
            Map.of(),
            NOW,
            null,
            NOW,
            List.of());
    when(executions.listWaitDue(any(), eq(50))).thenReturn(List.of(notWait));
    assertThat(engine.processDueWaits(50)).isEqualTo(0);

    AutomationWorkflow waitTerminal =
        new AutomationWorkflow(
            WF,
            "w",
            "d",
            "invoice_overdue",
            List.of(new WorkflowStep("s1", StepType.WAIT, null, Map.of(), 1, null, null, null)),
            WorkflowStatus.ACTIVE,
            1,
            false,
            null,
            NOW,
            NOW);
    when(store.findById(WF)).thenReturn(Optional.of(waitTerminal));
    WorkflowExecution waitDone =
        new WorkflowExecution(
            UUID.randomUUID(),
            WF,
            1,
            "PHARMACY",
            UUID.randomUUID(),
            "n",
            "s1",
            WorkflowExecutionStatus.RUNNING,
            NOW.minusSeconds(1),
            Map.of(),
            NOW,
            null,
            NOW,
            List.of());
    when(executions.listWaitDue(any(), eq(50))).thenReturn(List.of(waitDone));
    assertThat(engine.processDueWaits(50)).isEqualTo(1);

    when(actionExecutor.execute(anyString(), anyMap(), anyMap()))
        .thenThrow(new RuntimeException("boom"));
    AutomationWorkflow actionOnly =
        new AutomationWorkflow(
            WF,
            "a",
            "d",
            "invoice_overdue",
            List.of(new WorkflowStep("s1", StepType.ACTION, null, Map.of(), null, null, " ", null)),
            WorkflowStatus.ACTIVE,
            1,
            false,
            null,
            NOW,
            NOW);
    when(executions.findRunning(any(), any())).thenReturn(Optional.empty());
    assertThat(engine.start(actionOnly, "PHARMACY", UUID.randomUUID(), "n", Map.of())).isPresent();

    AutomationWorkflow branchNullCond =
        new AutomationWorkflow(
            WF,
            "b",
            "d",
            "invoice_overdue",
            List.of(
                new WorkflowStep("s1", StepType.BRANCH, null, Map.of(), null, null, null, null)),
            WorkflowStatus.ACTIVE,
            1,
            false,
            null,
            NOW,
            NOW);
    assertThat(engine.start(branchNullCond, "PHARMACY", UUID.randomUUID(), "n", Map.of()))
        .isPresent();

    AutomationWorkflow weird = sample(WorkflowStatus.ACTIVE);

    // null current step → complete via reflection
    java.lang.reflect.Method advance =
        WorkflowEngineService.class.getDeclaredMethod(
            "advanceUntilBlocked", AutomationWorkflow.class, WorkflowExecution.class);
    advance.setAccessible(true);
    advance.invoke(
        engine,
        weird,
        new WorkflowExecution(
            UUID.randomUUID(),
            WF,
            1,
            "PHARMACY",
            UUID.randomUUID(),
            "n",
            null,
            WorkflowExecutionStatus.RUNNING,
            null,
            Map.of(),
            NOW,
            null,
            null,
            List.of()));
    // unknown step → fail
    advance.invoke(
        engine,
        weird,
        new WorkflowExecution(
            UUID.randomUUID(),
            WF,
            1,
            "PHARMACY",
            UUID.randomUUID(),
            "n",
            "ghost",
            WorkflowExecutionStatus.RUNNING,
            null,
            Map.of(),
            NOW,
            null,
            null,
            List.of()));
    // already terminal status short-circuit
    advance.invoke(
        engine,
        weird,
        new WorkflowExecution(
            UUID.randomUUID(),
            WF,
            1,
            "PHARMACY",
            UUID.randomUUID(),
            "n",
            "s1",
            WorkflowExecutionStatus.PAUSED,
            null,
            Map.of(),
            NOW,
            null,
            null,
            List.of()));
    // waitUntil set short-circuit
    advance.invoke(
        engine,
        weird,
        new WorkflowExecution(
            UUID.randomUUID(),
            WF,
            1,
            "PHARMACY",
            UUID.randomUUID(),
            "n",
            "s1",
            WorkflowExecutionStatus.RUNNING,
            NOW.plusSeconds(10),
            Map.of(),
            NOW,
            null,
            null,
            List.of()));

    when(store.listActiveByTrigger("invoice_overdue")).thenReturn(List.of(weird));
    when(executions.findRunning(any(), any())).thenReturn(Optional.empty());
    assertThat(engine.onTrigger("invoice_overdue", "PHARMACY", UUID.randomUUID(), "Shop", Map.of()))
        .hasSize(1);

    Class<?> guard =
        Class.forName(
            "com.nammamedmate.automation.application.WorkflowEngineService$WorkflowStepValidatorGuard");
    Constructor<?> gctor = guard.getDeclaredConstructor();
    gctor.setAccessible(true);
    gctor.newInstance();

    verify(executions, org.mockito.Mockito.atLeastOnce()).update(any());
  }

  @Test
  void validatorAndControllerAndJdbcGaps() throws Exception {
    Constructor<WorkflowStepValidator> ctor = WorkflowStepValidator.class.getDeclaredConstructor();
    ctor.setAccessible(true);
    ctor.newInstance();

    assertThatThrownBy(
            () -> {
              java.util.ArrayList<WorkflowStep> steps = new java.util.ArrayList<>();
              steps.add(null);
              WorkflowStepValidator.validate(steps);
            })
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                WorkflowStepValidator.validate(
                    List.of(
                        new WorkflowStep(
                            "s1", null, "send_notification", Map.of(), null, null, null, null))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                WorkflowStepValidator.validate(
                    List.of(
                        new WorkflowStep(
                            "s1",
                            StepType.ACTION,
                            "send_notification",
                            Map.of(),
                            null,
                            null,
                            null,
                            null),
                        new WorkflowStep(
                            "s1",
                            StepType.ACTION,
                            "send_notification",
                            Map.of(),
                            null,
                            null,
                            null,
                            null))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    WorkflowStepValidator.validate(
        List.of(
            new WorkflowStep(
                "s1", StepType.ACTION, "send_notification", Map.of(), null, null, "s2", "s2"),
            new WorkflowStep(
                "s2", StepType.ACTION, "send_notification", Map.of(), null, null, null, null)));

    AdminAutomationWorkflowsController ctrl = new AdminAutomationWorkflowsController(management);
    when(management.patch(any(), any(), any(), any(), any(), any())).thenReturn(Map.of("v", 2));
    when(management.toggle(any(), any(), any())).thenReturn(Map.of("status", "ACTIVE"));
    when(management.create(any(), any(), any(), any(), any())).thenReturn(Map.of("ok", true));
    ctrl.patch(
        superAdmin,
        WF,
        new AdminAutomationWorkflowsController.PatchWorkflowRequest(
            "n",
            "d",
            "invoice_overdue",
            List.of(
                new AdminAutomationWorkflowsController.StepDto(
                    "s1", "BADTYPE", null, null, null, null, null, null))));
    ctrl.toggle(superAdmin, WF, null);
    ctrl.create(
        superAdmin,
        new AdminAutomationWorkflowsController.CreateWorkflowRequest(
            "n",
            "d",
            "invoice_overdue",
            List.of(
                new AdminAutomationWorkflowsController.StepDto(
                    "s1",
                    "BRANCH",
                    null,
                    Map.of(),
                    null,
                    new AdminAutomationWorkflowsController.ConditionDto("a", "eq", 1),
                    null,
                    null))));

    InternalRulesEvaluateController evaluate =
        new InternalRulesEvaluateController(rulesEngine, engine, new InternalAutomationAuth("tok"));
    when(rulesEngine.evaluate(any())).thenReturn(Map.of("ok", true));
    when(store.listActiveByTrigger(anyString())).thenReturn(List.of());
    evaluate.evaluate(
        "tok",
        new InternalRulesEvaluateController.EvaluateRequest(
            null,
            new InternalRulesEvaluateController.EventDto(
                "invoice_overdue",
                "PHARMACY",
                UUID.randomUUID(),
                Map.of("entity_name", "Shop"),
                NOW),
            false,
            null,
            null,
            null));

    when(rs.getObject("id")).thenReturn(WF);
    when(rs.getString("name")).thenReturn("WF");
    when(rs.getString("description")).thenReturn(null);
    when(rs.getString("trigger_id")).thenReturn("invoice_overdue");
    when(rs.getString("steps")).thenReturn("");
    when(rs.getString("status")).thenReturn("ACTIVE");
    when(rs.getInt("version")).thenReturn(1);
    when(rs.getBoolean("is_seed_workflow")).thenReturn(true);
    when(rs.getObject("created_by")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(null);
    when(rs.getTimestamp("updated_at")).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    JdbcWorkflowStoreAdapter storeAdapter = new JdbcWorkflowStoreAdapter(jdbc, new ObjectMapper());
    assertThat(storeAdapter.findById(WF)).isPresent();

    when(boomMapper.writeValueAsString(any())).thenThrow(new RuntimeException("x"));
    when(boomMapper.readValue(
            anyString(), any(com.fasterxml.jackson.core.type.TypeReference.class)))
        .thenThrow(new RuntimeException("x"));
    JdbcWorkflowStoreAdapter boomStore = new JdbcWorkflowStoreAdapter(jdbc, boomMapper);
    boomStore.insert(sample(WorkflowStatus.INACTIVE));
    when(rs.getString("steps")).thenReturn("{bad");
    // re-query with boom mapper path via empty catch on read — use good jdbc stub
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              when(rs.getString("steps")).thenReturn("{bad");
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(new JdbcWorkflowStoreAdapter(jdbc, new ObjectMapper()).listAll().getFirst().steps())
        .isEmpty();

    when(rs.getObject("workflow_id")).thenReturn(WF);
    when(rs.getInt("workflow_version")).thenReturn(1);
    when(rs.getString("entity_type")).thenReturn("PHARMACY");
    when(rs.getObject("entity_id")).thenReturn(WF);
    when(rs.getString("entity_name")).thenReturn(null);
    when(rs.getString("current_step_id")).thenReturn("s1");
    when(rs.getString("status")).thenReturn("COMPLETED");
    when(rs.getTimestamp("wait_until")).thenReturn(null);
    when(rs.getString("context")).thenReturn("");
    when(rs.getTimestamp("started_at")).thenReturn(null);
    when(rs.getTimestamp("completed_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getTimestamp("last_step_executed_at")).thenReturn(null);
    when(rs.getString("step_history")).thenReturn("");
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(null);
    JdbcWorkflowExecutionAdapter execAdapter =
        new JdbcWorkflowExecutionAdapter(jdbc, new ObjectMapper());
    assertThat(execAdapter.count(WF, null)).isZero();
    assertThat(execAdapter.count(WF, WorkflowExecutionStatus.RUNNING)).isZero();
    assertThat(execAdapter.countByWorkflowAndStatus(WF, WorkflowExecutionStatus.RUNNING)).isZero();
    assertThat(execAdapter.countCompletedSince(WF, NOW)).isZero();
    WorkflowExecution completed =
        new WorkflowExecution(
            WF,
            WF,
            1,
            "PHARMACY",
            WF,
            null,
            "s1",
            WorkflowExecutionStatus.COMPLETED,
            null,
            Map.of(),
            NOW,
            NOW,
            null,
            List.of());
    when(jdbc.update(
            anyString(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);
    execAdapter.insert(completed);
    execAdapter.update(completed);
    JdbcWorkflowExecutionAdapter boomExec = new JdbcWorkflowExecutionAdapter(jdbc, boomMapper);
    boomExec.insert(completed);

    assertThat(WorkflowExecutionStatus.parse(null)).isNull();
    assertThat(StepType.parse(" ")).isNull();
    assertThat(WorkflowStatus.parse(null)).isNull();
    assertThat(sample(WorkflowStatus.ACTIVE).stepById(null)).isNull();
    assertThat(new WorkflowStep("s", StepType.ACTION, "a", null, null, null, null, null).params())
        .isEmpty();
  }

  @Test
  void remainingCoverageBranches() throws Exception {
    // create with null steps list
    assertThatThrownBy(() -> mgmt.create(superAdmin, "NullSteps", "d", "invoice_overdue", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    AutomationWorkflow existing = sample(WorkflowStatus.ACTIVE);
    when(store.findById(WF)).thenReturn(Optional.of(existing));
    when(store.findByNameIgnoreCase("DUNNING")).thenReturn(Optional.of(existing));
    when(executions.pauseRunning(WF)).thenReturn(1);
    // same name (case-insensitive) should not conflict
    Map<String, Object> patched = mgmt.patch(superAdmin, WF, "dunning", "new-desc", null, null);
    assertThat(patched.get("version")).isEqualTo(2);

    assertThat(WorkflowExecutionStatus.parse("  ")).isNull();
    assertThat(
            new AutomationWorkflow(
                    WF, "n", "d", "t", null, WorkflowStatus.INACTIVE, 1, false, null, NOW, NOW)
                .steps())
        .isEmpty();
    assertThat(
            new WorkflowExecution(
                    WF,
                    WF,
                    1,
                    "P",
                    WF,
                    null,
                    "s1",
                    WorkflowExecutionStatus.RUNNING,
                    null,
                    null,
                    NOW,
                    null,
                    null,
                    null)
                .context())
        .isEmpty();

    // MAX_ITERS cycle exhaust
    when(executions.findRunning(any(), any())).thenReturn(Optional.empty());
    org.mockito.Mockito.doNothing().when(executions).insert(any());
    org.mockito.Mockito.doNothing().when(executions).update(any());
    AutomationWorkflow cycle =
        new AutomationWorkflow(
            WF,
            "c",
            "d",
            "invoice_overdue",
            List.of(
                new WorkflowStep(
                    "s1", StepType.ACTION, "send_notification", Map.of(), null, null, "s2", null),
                new WorkflowStep(
                    "s2", StepType.ACTION, "send_notification", Map.of(), null, null, "s1", null)),
            WorkflowStatus.ACTIVE,
            1,
            false,
            null,
            NOW,
            NOW);
    assertThat(engine.start(cycle, null, UUID.randomUUID(), null, Map.of())).isPresent();

    // JDBC: condition serialization + null steps + non-number wait hours
    when(rs.getObject("id")).thenReturn(WF);
    when(rs.getString("name")).thenReturn("WF");
    when(rs.getString("description")).thenReturn("d");
    when(rs.getString("trigger_id")).thenReturn("invoice_overdue");
    when(rs.getString("steps")).thenReturn(null);
    when(rs.getString("status")).thenReturn("ACTIVE");
    when(rs.getInt("version")).thenReturn(1);
    when(rs.getBoolean("is_seed_workflow")).thenReturn(false);
    when(rs.getObject("created_by")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(NOW));
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    JdbcWorkflowStoreAdapter storeAdapter = new JdbcWorkflowStoreAdapter(jdbc, new ObjectMapper());
    assertThat(storeAdapter.findById(WF).orElseThrow().steps()).isEmpty();

    AutomationWorkflow withCond =
        new AutomationWorkflow(
            WF,
            "WF",
            "d",
            "invoice_overdue",
            List.of(
                new WorkflowStep(
                    "s1",
                    StepType.BRANCH,
                    null,
                    Map.of(),
                    null,
                    new ConditionSpec("a", "eq", true),
                    null,
                    null)),
            WorkflowStatus.INACTIVE,
            1,
            false,
            null,
            NOW,
            NOW);
    storeAdapter.insert(withCond);

    when(rs.getString("steps"))
        .thenReturn(
            "[{\"step_id\":\"s1\",\"type\":\"WAIT\",\"wait_duration_hours\":\"nope\",\"params\":{}}]");
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(storeAdapter.listAll().getFirst().steps().getFirst().waitDurationHours()).isNull();

    // execution mapping both timestamp sides + null json + list json exception
    when(rs.getObject("workflow_id")).thenReturn(WF);
    when(rs.getInt("workflow_version")).thenReturn(1);
    when(rs.getString("entity_type")).thenReturn("PHARMACY");
    when(rs.getObject("entity_id")).thenReturn(WF);
    when(rs.getString("entity_name")).thenReturn("n");
    when(rs.getString("current_step_id")).thenReturn("s1");
    when(rs.getString("status")).thenReturn("RUNNING");
    when(rs.getTimestamp("wait_until")).thenReturn(Timestamp.from(NOW));
    when(rs.getString("context")).thenReturn(null);
    when(rs.getTimestamp("started_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getTimestamp("completed_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getTimestamp("last_step_executed_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getString("step_history")).thenReturn(null);
    JdbcWorkflowExecutionAdapter execAdapter =
        new JdbcWorkflowExecutionAdapter(jdbc, new ObjectMapper());
    assertThat(execAdapter.findById(WF)).isPresent();

    ObjectMapper boom = org.mockito.Mockito.mock(ObjectMapper.class);
    when(boom.writeValueAsString(any())).thenThrow(new RuntimeException("x"));
    JdbcWorkflowExecutionAdapter boomExec = new JdbcWorkflowExecutionAdapter(jdbc, boom);
    boomExec.insert(
        new WorkflowExecution(
            WF,
            WF,
            1,
            "P",
            WF,
            null,
            "s1",
            WorkflowExecutionStatus.RUNNING,
            null,
            null,
            NOW,
            null,
            null,
            List.of(Map.of("a", 1))));

    InternalRulesEvaluateController evaluate =
        new InternalRulesEvaluateController(rulesEngine, engine, new InternalAutomationAuth("tok"));
    when(rulesEngine.evaluate(any())).thenReturn(Map.of("ok", true));
    when(store.listActiveByTrigger(anyString())).thenReturn(List.of());
    evaluate.evaluate(
        "tok",
        new InternalRulesEvaluateController.EvaluateRequest(
            null,
            new InternalRulesEvaluateController.EventDto(
                "invoice_overdue", "PHARMACY", UUID.randomUUID(), Map.of("x", 1), NOW),
            false,
            null,
            null,
            null));

    // cancel CANCELLED terminal
    UUID exec = UUID.randomUUID();
    when(executions.findById(exec))
        .thenReturn(
            Optional.of(
                new WorkflowExecution(
                    exec,
                    WF,
                    1,
                    "PHARMACY",
                    UUID.randomUUID(),
                    "x",
                    "s1",
                    WorkflowExecutionStatus.CANCELLED,
                    null,
                    Map.of(),
                    NOW,
                    NOW,
                    NOW,
                    List.of())));
    assertThatThrownBy(() -> mgmt.cancel(superAdmin, WF, exec))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    // list executions with blank-looking status that parses oddly — use page < 1
    when(executions.count(WF, null)).thenReturn(0L);
    when(executions.list(eq(WF), eq(null), eq(0), eq(20))).thenReturn(List.of());
    assertThat(mgmt.listExecutions(superAdmin, WF, null, 0).meta().page()).isEqualTo(1);

    // execution item with unknown step
    when(executions.count(WF, null)).thenReturn(1L);
    when(executions.list(eq(WF), eq(null), eq(0), eq(20)))
        .thenReturn(
            List.of(
                new WorkflowExecution(
                    exec,
                    WF,
                    1,
                    "PHARMACY",
                    UUID.randomUUID(),
                    "x",
                    "unknown",
                    WorkflowExecutionStatus.RUNNING,
                    null,
                    Map.of(),
                    NOW,
                    null,
                    NOW,
                    List.of())));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items =
        (List<Map<String, Object>>)
            mgmt.listExecutions(superAdmin, WF, null, 1).data().get("executions");
    assertThat(items.getFirst().get("current_step_type")).isNull();

    assertThatThrownBy(() -> WorkflowStepValidator.validate(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                WorkflowStepValidator.validate(
                    List.of(
                        new WorkflowStep(
                            "  ",
                            StepType.ACTION,
                            "send_notification",
                            Map.of(),
                            null,
                            null,
                            null,
                            null))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                WorkflowStepValidator.validate(
                    List.of(
                        new WorkflowStep(
                            "s1", StepType.ACTION, "  ", Map.of(), null, null, null, null))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                WorkflowStepValidator.validate(
                    List.of(
                        new WorkflowStep(
                            "s1", StepType.WAIT, null, Map.of(), -1, null, null, null))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                WorkflowStepValidator.validate(
                    List.of(
                        new WorkflowStep(
                            "s1",
                            StepType.BRANCH,
                            null,
                            Map.of(),
                            null,
                            new ConditionSpec("f", "  ", 1),
                            null,
                            null))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    WorkflowStepValidator.validate(
        List.of(
            new WorkflowStep(
                "s1", StepType.ACTION, "send_notification", Map.of(), null, null, "  ", "  ")));

    // patch with explicit steps + null description already set name
    when(store.findByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
    mgmt.patch(
        superAdmin,
        WF,
        null,
        null,
        null,
        List.of(
            new WorkflowStep(
                "s1", StepType.ACTION, "send_notification", Map.of(), null, null, null, null)));

    // processDueWaits with null step (unknown current_step)
    when(store.findById(WF)).thenReturn(Optional.of(sample(WorkflowStatus.ACTIVE)));
    when(executions.listWaitDue(any(), eq(10)))
        .thenReturn(
            List.of(
                new WorkflowExecution(
                    UUID.randomUUID(),
                    WF,
                    1,
                    "PHARMACY",
                    UUID.randomUUID(),
                    "n",
                    "missing",
                    WorkflowExecutionStatus.RUNNING,
                    NOW.minusSeconds(1),
                    Map.of(),
                    NOW,
                    null,
                    NOW,
                    List.of())));
    assertThat(engine.processDueWaits(10)).isEqualTo(0);

    // jdbc timestamp null sides + blank vs null json
    when(rs.getObject("id")).thenReturn(WF);
    when(rs.getObject("workflow_id")).thenReturn(WF);
    when(rs.getInt("workflow_version")).thenReturn(1);
    when(rs.getString("entity_type")).thenReturn("PHARMACY");
    when(rs.getObject("entity_id")).thenReturn(WF);
    when(rs.getString("entity_name")).thenReturn(null);
    when(rs.getString("current_step_id")).thenReturn("s1");
    when(rs.getString("status")).thenReturn("RUNNING");
    when(rs.getTimestamp("wait_until")).thenReturn(null);
    when(rs.getString("context")).thenReturn("   ");
    when(rs.getTimestamp("started_at")).thenReturn(null);
    when(rs.getTimestamp("completed_at")).thenReturn(null);
    when(rs.getTimestamp("last_step_executed_at")).thenReturn(null);
    when(rs.getString("step_history")).thenReturn("   ");
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    JdbcWorkflowExecutionAdapter execAdapter2 =
        new JdbcWorkflowExecutionAdapter(jdbc, new ObjectMapper());
    assertThat(execAdapter2.findById(WF).orElseThrow().waitUntil()).isNull();
    assertThat(execAdapter2.findById(WF).orElseThrow().startedAt()).isEqualTo(Instant.EPOCH);

    ObjectMapper boom2 = org.mockito.Mockito.mock(ObjectMapper.class);
    when(boom2.writeValueAsString(any())).thenThrow(new RuntimeException("x"));
    new JdbcWorkflowExecutionAdapter(jdbc, boom2)
        .update(
            new WorkflowExecution(
                WF,
                WF,
                1,
                "P",
                WF,
                null,
                "s1",
                WorkflowExecutionStatus.RUNNING,
                null,
                null,
                NOW,
                null,
                null,
                null));

    assertThat(StepType.parse(null)).isNull();

    when(rulesEngine.evaluate(any())).thenReturn(Map.of("ok", true));
    when(store.listActiveByTrigger(anyString())).thenReturn(List.of());
    new InternalRulesEvaluateController(rulesEngine, engine, new InternalAutomationAuth("tok"))
        .evaluate(
            "tok",
            new InternalRulesEvaluateController.EvaluateRequest(
                null,
                new InternalRulesEvaluateController.EventDto(
                    "invoice_overdue",
                    "PHARMACY",
                    UUID.randomUUID(),
                    Map.of("entity_name", "Shop"),
                    NOW),
                true,
                null,
                null,
                null));

    assertThat(
            engine.start(sample(WorkflowStatus.ACTIVE), "PHARMACY", UUID.randomUUID(), "n", null))
        .isPresent();

    // patch rename without conflict
    when(store.findByNameIgnoreCase("Renamed")).thenReturn(Optional.empty());
    mgmt.patch(superAdmin, WF, "Renamed", null, null, null);

    // get with BRANCH+/- condition for stepsPayload
    AutomationWorkflow mixed =
        new AutomationWorkflow(
            WF,
            "mixed",
            "d",
            "invoice_overdue",
            List.of(
                new WorkflowStep(
                    "s1",
                    StepType.BRANCH,
                    null,
                    Map.of(),
                    null,
                    new ConditionSpec("a", "eq", 1),
                    null,
                    null),
                new WorkflowStep("s2", StepType.BRANCH, null, Map.of(), null, null, null, null),
                new WorkflowStep("s3", StepType.WAIT, null, Map.of(), 1, null, null, null)),
            WorkflowStatus.ACTIVE,
            1,
            false,
            null,
            NOW,
            NOW);
    when(store.findById(WF)).thenReturn(Optional.of(mixed));
    when(executions.countByWorkflowAndStatus(any(), any())).thenReturn(0L);
    when(executions.avgCompletionHours(WF)).thenReturn(null);
    assertThat(mgmt.get(superAdmin, WF).get("steps")).asList().hasSize(3);

    assertThatThrownBy(
            () ->
                WorkflowStepValidator.validate(
                    List.of(
                        new WorkflowStep(
                            null,
                            StepType.ACTION,
                            "send_notification",
                            Map.of(),
                            null,
                            null,
                            null,
                            null))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                WorkflowStepValidator.validate(
                    List.of(
                        new WorkflowStep(
                            "s1",
                            StepType.BRANCH,
                            null,
                            Map.of(),
                            null,
                            new ConditionSpec("f", null, 1),
                            null,
                            null))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    // evaluate with null payload enters onTrigger path
    when(store.listActiveByTrigger(anyString())).thenReturn(List.of());
    new InternalRulesEvaluateController(rulesEngine, engine, new InternalAutomationAuth("tok"))
        .evaluate(
            "tok",
            new InternalRulesEvaluateController.EvaluateRequest(
                null,
                new InternalRulesEvaluateController.EventDto(
                    "invoice_overdue", "PHARMACY", UUID.randomUUID(), null, NOW),
                false,
                null,
                null,
                null));
    // triggerId null skips workflows
    new InternalRulesEvaluateController(rulesEngine, engine, new InternalAutomationAuth("tok"))
        .evaluate(
            "tok",
            new InternalRulesEvaluateController.EvaluateRequest(
                null,
                new InternalRulesEvaluateController.EventDto(
                    null, "PHARMACY", UUID.randomUUID(), Map.of(), NOW),
                false,
                null,
                null,
                null));

    // created_by non-null + wait hours number
    when(rs.getObject("created_by")).thenReturn(WF);
    when(rs.getTimestamp("created_at")).thenReturn(null);
    when(rs.getString("status")).thenReturn("ACTIVE");
    when(rs.getString("name")).thenReturn("WF");
    when(rs.getString("trigger_id")).thenReturn("invoice_overdue");
    when(rs.getInt("version")).thenReturn(1);
    when(rs.getBoolean("is_seed_workflow")).thenReturn(false);
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getString("steps"))
        .thenReturn(
            "[{\"step_id\":\"s1\",\"type\":\"WAIT\",\"wait_duration_hours\":3,\"params\":{}}]");
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(
            new JdbcWorkflowStoreAdapter(jdbc, new ObjectMapper())
                .findById(WF)
                .orElseThrow()
                .createdBy())
        .isEqualTo(WF);

    assertThatThrownBy(() -> mgmt.create(superAdmin, null, "d", "invoice_overdue", List.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(null);
    assertThat(
            new JdbcWorkflowStoreAdapter(jdbc, new ObjectMapper())
                .countByStatus(WorkflowStatus.ACTIVE))
        .isZero();
    when(rs.getString("steps"))
        .thenReturn("[{\"step_id\":\"s1\",\"type\":\"ACTION\",\"params\":\"nope\"}]");
    assertThat(
            new JdbcWorkflowStoreAdapter(jdbc, new ObjectMapper())
                .findById(WF)
                .orElseThrow()
                .steps()
                .getFirst()
                .params())
        .isEmpty();
    new InternalRulesEvaluateController(rulesEngine, engine, new InternalAutomationAuth("tok"))
        .evaluate(
            "tok",
            new InternalRulesEvaluateController.EvaluateRequest(
                null,
                new InternalRulesEvaluateController.EventDto(
                    "invoice_overdue", "PHARMACY", null, Map.of("entity_name", "x"), NOW),
                false,
                null,
                null,
                null));
  }

  private AutomationWorkflow sample(WorkflowStatus status) {
    return new AutomationWorkflow(
        WF,
        "DUNNING",
        "d",
        "invoice_overdue",
        List.of(
            new WorkflowStep(
                "s1", StepType.ACTION, "send_notification", Map.of(), null, null, null, null)),
        status,
        1,
        false,
        ADMIN,
        NOW,
        NOW);
  }
}
