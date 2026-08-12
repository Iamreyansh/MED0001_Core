package com.nammamedmate.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.automation.adapter.out.persistence.JdbcWorkflowExecutionAdapter;
import com.nammamedmate.automation.adapter.out.persistence.JdbcWorkflowStoreAdapter;
import com.nammamedmate.automation.application.WorkflowEngineService;
import com.nammamedmate.automation.application.WorkflowWaitScheduler;
import com.nammamedmate.automation.domain.AutomationWorkflow;
import com.nammamedmate.automation.domain.ConditionSpec;
import com.nammamedmate.automation.domain.StepType;
import com.nammamedmate.automation.domain.WorkflowExecution;
import com.nammamedmate.automation.domain.WorkflowExecutionStatus;
import com.nammamedmate.automation.domain.WorkflowStatus;
import com.nammamedmate.automation.domain.WorkflowStep;
import com.nammamedmate.automation.domain.WorkflowStepValidator;
import com.nammamedmate.kernel.error.AppException;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
class CoverageStory003Test {

  @Mock JdbcTemplate jdbc;
  @Mock ResultSet rs;
  @Mock WorkflowEngineService engine;

  private final ObjectMapper om = new ObjectMapper();
  private final Instant now = Instant.parse("2026-07-24T09:00:00Z");
  private final UUID id = UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");

  @Test
  @SuppressWarnings("unchecked")
  void jdbcWorkflowStoreRoundTrip() throws Exception {
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getString("name")).thenReturn("WF");
    when(rs.getString("description")).thenReturn("d");
    when(rs.getString("trigger_id")).thenReturn("invoice_overdue");
    when(rs.getString("steps"))
        .thenReturn(
            """
            [{"step_id":"s1","type":"ACTION","action_id":"send_notification","params":{},\
            "wait_duration_hours":null,"condition":null,"next_step_id_on_true":null,\
            "next_step_id_on_false":null},\
            {"step_id":"s2","type":"WAIT","action_id":null,"params":{},\
            "wait_duration_hours":24,"condition":null,"next_step_id_on_true":null,\
            "next_step_id_on_false":null},\
            {"step_id":"s3","type":"BRANCH","action_id":null,"params":{},\
            "wait_duration_hours":null,"condition":{"field":"a","operator":"eq","value":true},\
            "next_step_id_on_true":null,"next_step_id_on_false":null}]
            """);
    when(rs.getString("status")).thenReturn("INACTIVE");
    when(rs.getInt("version")).thenReturn(1);
    when(rs.getBoolean("is_seed_workflow")).thenReturn(false);
    when(rs.getObject("created_by")).thenReturn(id);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(1L);

    JdbcWorkflowStoreAdapter store = new JdbcWorkflowStoreAdapter(jdbc, om);
    assertThat(store.findById(id)).isPresent();
    assertThat(store.findByNameIgnoreCase("WF")).isPresent();
    assertThat(store.listAll()).hasSize(1);
    assertThat(store.listActiveByTrigger("invoice_overdue")).hasSize(1);
    assertThat(store.countByStatus(WorkflowStatus.ACTIVE)).isEqualTo(1);

    AutomationWorkflow wf =
        new AutomationWorkflow(
            id,
            "WF",
            "d",
            "invoice_overdue",
            List.of(
                new WorkflowStep(
                    "s1", StepType.ACTION, "send_notification", Map.of(), null, null, null, null)),
            WorkflowStatus.INACTIVE,
            1,
            false,
            id,
            now,
            now);
    store.insert(wf);
    store.update(wf);
    verify(jdbc, org.mockito.Mockito.atLeastOnce())
        .update(
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
            any());

    when(rs.getString("steps")).thenReturn("not-json");
    assertThat(store.listAll().getFirst().steps()).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void jdbcExecutionAdapter() throws Exception {
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getObject("workflow_id")).thenReturn(id);
    when(rs.getInt("workflow_version")).thenReturn(1);
    when(rs.getString("entity_type")).thenReturn("PHARMACY");
    when(rs.getObject("entity_id")).thenReturn(id);
    when(rs.getString("entity_name")).thenReturn("Shop");
    when(rs.getString("current_step_id")).thenReturn("s2");
    when(rs.getString("status")).thenReturn("RUNNING");
    when(rs.getTimestamp("wait_until")).thenReturn(Timestamp.from(now.plusSeconds(3600)));
    when(rs.getString("context")).thenReturn("{\"a\":1}");
    when(rs.getTimestamp("started_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("completed_at")).thenReturn(null);
    when(rs.getTimestamp("last_step_executed_at")).thenReturn(Timestamp.from(now));
    when(rs.getString("step_history")).thenReturn("[]");
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(2L);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(1L);
    when(jdbc.queryForObject(anyString(), eq(Double.class), any())).thenReturn(10.5);
    when(jdbc.update(anyString(), any(UUID.class))).thenReturn(3);
    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);
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

    JdbcWorkflowExecutionAdapter adapter = new JdbcWorkflowExecutionAdapter(jdbc, om);
    WorkflowExecution ex =
        new WorkflowExecution(
            id,
            id,
            1,
            "PHARMACY",
            id,
            "Shop",
            "s2",
            WorkflowExecutionStatus.RUNNING,
            now.plusSeconds(3600),
            Map.of("a", 1),
            now,
            null,
            now,
            List.of());
    adapter.insert(ex);
    adapter.update(ex);
    assertThat(adapter.findById(id)).isPresent();
    assertThat(adapter.findRunning(id, id)).isPresent();
    assertThat(adapter.countByWorkflowAndStatus(id, WorkflowExecutionStatus.RUNNING)).isEqualTo(1);
    assertThat(adapter.countCompletedSince(id, now)).isEqualTo(1);
    assertThat(adapter.avgCompletionHours(id)).isEqualTo(10.5);
    assertThat(adapter.pauseRunning(id)).isEqualTo(3);
    assertThat(adapter.list(id, null, 0, 20)).hasSize(1);
    assertThat(adapter.list(id, WorkflowExecutionStatus.RUNNING, 0, 20)).hasSize(1);
    assertThat(adapter.count(id, null)).isEqualTo(2);
    assertThat(adapter.count(id, WorkflowExecutionStatus.RUNNING)).isEqualTo(1);
    assertThat(adapter.listWaitDue(now, 10)).hasSize(1);

    when(rs.getString("context")).thenReturn("bad");
    when(rs.getString("step_history")).thenReturn("bad");
    assertThat(adapter.findById(id).orElseThrow().context()).isEmpty();
  }

  @Test
  void enumsAndValidatorAndScheduler() {
    assertThat(WorkflowStatus.parse("active")).isEqualTo(WorkflowStatus.ACTIVE);
    assertThat(WorkflowStatus.parse(" ")).isNull();
    assertThat(WorkflowExecutionStatus.parse("paused")).isEqualTo(WorkflowExecutionStatus.PAUSED);
    assertThat(StepType.parse("wait")).isEqualTo(StepType.WAIT);
    assertThatThrownBy(() -> StepType.parse("NOPE")).isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> WorkflowStepValidator.validate(List.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                WorkflowStepValidator.validate(
                    List.of(
                        new WorkflowStep(
                            "s1", StepType.WAIT, null, Map.of(), null, null, null, null))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                WorkflowStepValidator.validate(
                    List.of(
                        new WorkflowStep(
                            "s1", StepType.BRANCH, null, Map.of(), null, null, null, null))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                WorkflowStepValidator.validate(
                    List.of(
                        new WorkflowStep(
                            "s1", StepType.ACTION, null, Map.of(), null, null, null, null))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    WorkflowStepValidator.validate(
        List.of(
            new WorkflowStep(
                "s1",
                StepType.BRANCH,
                null,
                Map.of(),
                null,
                new ConditionSpec("f", "eq", 1),
                null,
                null)));

    when(engine.processDueWaits(100)).thenReturn(2);
    new WorkflowWaitScheduler(engine).advanceDueWaits();
    verify(engine).processDueWaits(100);

    AutomationWorkflow wf =
        new AutomationWorkflow(
            id, "n", "d", "t", List.of(), WorkflowStatus.ACTIVE, 1, false, null, now, now);
    assertThat(wf.entryStep()).isNull();
    assertThat(wf.stepById("x")).isNull();
  }
}
