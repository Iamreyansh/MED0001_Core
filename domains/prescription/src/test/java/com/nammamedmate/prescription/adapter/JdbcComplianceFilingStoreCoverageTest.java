package com.nammamedmate.prescription.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.prescription.adapter.out.persistence.JdbcComplianceFilingStore;
import com.nammamedmate.prescription.application.port.out.ComplianceFilingStore.ActivityFilter;
import com.nammamedmate.prescription.application.port.out.ComplianceFilingStore.GenerateJob;
import com.nammamedmate.prescription.application.port.out.ComplianceFilingStore.ListFilter;
import com.nammamedmate.prescription.domain.ComplianceFiling;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcComplianceFilingStoreCoverageTest {

  private final ObjectMapper om = new ObjectMapper().findAndRegisterModules();
  private final Instant now = Instant.parse("2026-07-01T00:00:00Z");

  @Test
  @SuppressWarnings("unchecked")
  void crudAndActivity() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcComplianceFilingStore store = new JdbcComplianceFilingStore(jdbc, om);
    UUID id = Ids.newId();
    ComplianceFiling filing =
        new ComplianceFiling(
            id,
            "SCHEDULE_H1_REGISTER",
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 30),
            LocalDate.of(2026, 7, 15),
            "PENDING",
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            null,
            now,
            now);
    store.insert(filing);
    store.update(filing);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              ResultSet rs = mockFilingRs(id);
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(store.findById(id)).isPresent();

    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(1);
    assertThat(
            store.existsTypePeriod(
                "SCHEDULE_H1_REGISTER", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)))
        .isTrue();

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              ResultSet rs = mockFilingRs(id);
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(
            store
                .list(new ListFilter("SCHEDULE_H1_REGISTER", "PENDING", 2026, false, 1, 20))
                .filings())
        .hasSize(1);

    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(2);
    when(jdbc.update(anyString(), any(), any(), any())).thenReturn(2);
    when(jdbc.update(anyString(), any(), any())).thenReturn(2);
    store.markOverdue(LocalDate.of(2026, 7, 15), now);
    store.setOverdueAlerted(id, now);
    store.setOverdueEscalation(id, now);
    store.archiveOlderThan(LocalDate.of(2021, 1, 1), now);

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              ResultSet rs = mockFilingRs(id);
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(store.findPendingPastDue(LocalDate.of(2026, 7, 15))).hasSize(1);
    assertThat(store.findOverdueForEscalation(LocalDate.of(2026, 7, 12))).hasSize(1);

    UUID jobId = Ids.newId();
    GenerateJob job =
        new GenerateJob(jobId, id, "CSV", "GENERATING", null, null, id, null, null, null, now);
    store.insertGenerateJob(job);
    store.updateGenerateJob(
        new GenerateJob(jobId, id, "CSV", "READY", "k", 1, id, now, now, null, now));
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              ResultSet rs = mockJobRs(jobId, id);
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(store.findGeneratingJob(id)).isPresent();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(jobId)))
        .thenAnswer(
            inv -> {
              ResultSet rs = mockJobRs(jobId, id);
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(store.findGenerateJob(jobId)).isPresent();

    store.appendActivity(
        Ids.newId(), null, null, id, "FILING_MARKED", id, "admin_compliance", "{}", null, now);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("action")).thenReturn("RX_VERIFIED");
              when(rs.getObject("actor_id")).thenReturn(id);
              when(rs.getString("actor_name")).thenReturn("Ananya");
              when(rs.getString("actor_role")).thenReturn("admin_compliance");
              when(rs.getObject("rx_id")).thenReturn(null);
              when(rs.getString("payload")).thenReturn("{\"verified\":true}");
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(
            store
                .listActivity(new ActivityFilter("RX_VERIFIED", id, now, now.plusSeconds(1), 1, 50))
                .items())
        .hasSize(1);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("name")).thenReturn("Ananya");
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(store.adminName(id)).contains("Ananya");
    verify(jdbc)
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
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any());
  }

  private ResultSet mockFilingRs(UUID id) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getString("filing_type")).thenReturn("SCHEDULE_H1_REGISTER");
    when(rs.getDate("period_from")).thenReturn(Date.valueOf("2026-06-01"));
    when(rs.getDate("period_to")).thenReturn(Date.valueOf("2026-06-30"));
    when(rs.getDate("due_date")).thenReturn(Date.valueOf("2026-07-15"));
    when(rs.getString("status")).thenReturn("PENDING");
    when(rs.getString("generated_report_s3_key")).thenReturn(null);
    when(rs.getString("generated_report_format")).thenReturn(null);
    when(rs.getTimestamp("generated_at")).thenReturn(null);
    when(rs.getObject("filed_by")).thenReturn(null);
    when(rs.getTimestamp("filed_at")).thenReturn(null);
    when(rs.getString("reference_number")).thenReturn(null);
    when(rs.getBoolean("is_archived")).thenReturn(false);
    when(rs.getTimestamp("overdue_alerted_at")).thenReturn(null);
    when(rs.getTimestamp("overdue_escalation_at")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    return rs;
  }

  private ResultSet mockJobRs(UUID jobId, UUID filingId) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(jobId);
    when(rs.getObject("filing_id")).thenReturn(filingId);
    when(rs.getString("format")).thenReturn("CSV");
    when(rs.getString("status")).thenReturn("GENERATING");
    when(rs.getString("storage_key")).thenReturn(null);
    when(rs.getObject("row_count")).thenReturn(null);
    when(rs.getObject("requested_by")).thenReturn(filingId);
    when(rs.getTimestamp("generated_at")).thenReturn(null);
    when(rs.getTimestamp("expires_at")).thenReturn(null);
    when(rs.getString("error_message")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    return rs;
  }
}
