package com.nammamedmate.analytics.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.analytics.application.port.out.AdminReportStore.JobRow;
import com.nammamedmate.analytics.application.port.out.AdminReportStore.ReportDefinition;
import com.nammamedmate.analytics.application.port.out.AdminReportStore.ScheduleRow;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JdbcAdminReportStoreTest {

  @Mock JdbcTemplate jdbc;
  @Mock ResultSet rs;
  @Mock Array sqlArray;

  @Test
  @SuppressWarnings("unchecked")
  void coversCrudEstimatesAndGenerators() throws Exception {
    JdbcAdminReportStore store = new JdbcAdminReportStore(jdbc);
    UUID id = UUID.randomUUID();
    Instant now = Instant.parse("2026-07-24T01:30:00Z");

    when(rs.getString(anyString())).thenReturn("GMV_COMMISSION_PAYOUTS");
    when(rs.getString("name")).thenReturn("GMV");
    when(rs.getString("category")).thenReturn("FINANCE");
    when(rs.getString("description")).thenReturn("d");
    when(rs.getString("default_cadence")).thenReturn("MONTHLY");
    when(rs.getString("default_format")).thenReturn("CSV");
    when(rs.getString("cadence")).thenReturn("WEEKLY");
    when(rs.getString("format")).thenReturn("CSV");
    when(rs.getString("trigger_type")).thenReturn("MANUAL");
    when(rs.getString("status")).thenReturn("COMPLETED");
    when(rs.getString("filters")).thenReturn("{}");
    when(rs.getString("s3_key")).thenReturn("k");
    when(rs.getString("download_url")).thenReturn("u");
    when(rs.getString("error_message")).thenReturn(null);
    when(rs.getString("generated_by_label")).thenReturn("SCHEDULER");
    when(rs.getString("month")).thenReturn("2026-07");
    when(rs.getString("pharmacy_name")).thenReturn("P");
    when(rs.getString("gstin")).thenReturn("G");
    when(rs.getString("pan")).thenReturn("PAN");
    when(rs.getString("rx_reference_no")).thenReturn("RX");
    when(rs.getString("patient_name")).thenReturn("Pat");
    when(rs.getString("drug_name")).thenReturn("Drug");
    when(rs.getString("cohort_week")).thenReturn("2026-W17");
    when(rs.getString("source")).thenReturn("ORGANIC");
    when(rs.getInt(anyString())).thenReturn(1);
    when(rs.getInt("retention_years")).thenReturn(2);
    when(rs.getInt("progress_pct")).thenReturn(100);
    when(rs.getLong(anyString())).thenReturn(10L);
    when(rs.getLong("tcs_collected_paise")).thenReturn(50L);
    when(rs.getBoolean("is_active")).thenReturn(true);
    when(rs.getBoolean("is_enabled")).thenReturn(true);
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getObject("updated_by")).thenReturn(id);
    when(rs.getObject("triggered_by")).thenReturn(id);
    when(rs.getObject("pharmacy_id")).thenReturn(id);
    when(rs.getObject("row_count")).thenReturn(10);
    when(rs.getObject("file_size_kb")).thenReturn(1);
    when(rs.getObject(1)).thenReturn(id);
    when(rs.getArray("email_recipients")).thenReturn(sqlArray);
    when(sqlArray.getArray()).thenReturn(new String[] {"a@b.com"});
    when(rs.getTimestamp(anyString())).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp(1)).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("next_run_at")).thenReturn(null);
    when(rs.getTimestamp("updated_at")).thenReturn(null);
    when(rs.getTimestamp("expires_at")).thenReturn(null);
    when(rs.getTimestamp("started_at")).thenReturn(null);
    when(rs.getTimestamp("completed_at")).thenReturn(null);
    when(rs.getTimestamp("dispensed_at")).thenReturn(Timestamp.from(now));
    when(rs.getDate(anyString())).thenReturn(Date.valueOf("2026-07-01"));
    when(rs.next()).thenReturn(true, false);

    when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              when(rs.next()).thenReturn(true, false);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Object>>any()))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.query(
            anyString(), ArgumentMatchers.<ResultSetExtractor<Object>>any(), any(Object[].class)))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<Object> ex = inv.getArgument(1);
              when(rs.next()).thenReturn(true, false);
              return ex.extractData(rs);
            });
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(0);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    assertThat(store.findDefinition("GMV_COMMISSION_PAYOUTS")).isPresent();
    assertThat(store.listDefinitions(null)).isNotEmpty();
    assertThat(store.listDefinitions("FINANCE")).isNotEmpty();
    assertThat(store.findSchedule("GMV_COMMISSION_PAYOUTS")).isPresent();
    store.upsertSchedule(
        new ScheduleRow(
            id, "GMV_COMMISSION_PAYOUTS", true, "WEEKLY", "CSV", List.of("a@b.com"), now, id, now));
    assertThat(store.lastCompletedAt("GMV_COMMISSION_PAYOUTS")).isEqualTo(now);
    store.insertJob(
        new JobRow(
            id,
            "GMV_COMMISSION_PAYOUTS",
            id,
            "MANUAL",
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 31),
            "{}",
            "CSV",
            "QUEUED",
            0,
            null,
            null,
            null,
            null,
            null,
            now,
            null,
            null,
            null));
    assertThat(store.findJob(id)).isPresent();
    assertThat(store.countActiveJobs(id)).isEqualTo(0);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(2);
    assertThat(store.countActiveJobs(id)).isEqualTo(2);
    assertThat(store.findQueuedJobIds(5)).isNotEmpty();
    assertThat(store.findTimedOutJobIds(now)).isNotEmpty();
    store.markJobRunning(id, now);
    store.markJobCompleted(id, 100, 1, 1, "k", "u", now, now);
    store.markJobFailed(id, "JOB_TIMEOUT", now);
    store.refreshDownloadUrl(id, "u2", now);
    assertThat(store.listHistory(null, now, 20, 0)).isNotEmpty();
    assertThat(store.listHistory("  ", now, 20, 0)).isNotEmpty();
    assertThat(store.listHistory("FINANCE", now, 20, 0)).isNotEmpty();
    assertThat(store.countHistory("FINANCE", now)).isEqualTo(0L);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(5L);
    assertThat(store.countHistory("FINANCE", now)).isEqualTo(5L);
    assertThat(store.findDueSchedules(now)).isNotEmpty();
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
    assertThat(store.ledgerTcsTotalPaise(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
        .isEqualTo(0L);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(5L);
    assertThat(store.ledgerTcsTotalPaise(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
        .isEqualTo(5L);

    LocalDate from = LocalDate.of(2026, 7, 1);
    LocalDate to = LocalDate.of(2026, 7, 31);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
    for (String reportId :
        List.of(
            "TAX_GSTR8_PREP",
            "COMPLIANCE_SCHEDULE_H",
            "SCHEDULE_X_REGISTER",
            "COHORT_RETENTION",
            "ACQUISITION_MIX",
            "SLA_BREACHES",
            "ORDER_FULFILMENT",
            "CANCELLATION_ANALYSIS",
            "RIDER_PERFORMANCE",
            "GMV_COMMISSION_PAYOUTS",
            "PLATFORM_PNL",
            "REFUND_SUMMARY",
            "SETTLEMENT_SUMMARY",
            "DRUG_RECALL_IMPACT",
            "UNKNOWN")) {
      store.estimateRows(reportId, from, to, Map.of());
      store.generateRows(reportId, from, to, Map.of());
    }

    when(rs.getArray("email_recipients")).thenReturn(null);
    assertThat(store.findSchedule("X")).isPresent();
    when(rs.getArray("email_recipients")).thenReturn(sqlArray);
    when(sqlArray.getArray()).thenReturn(new Object[] {"x@y.com"});
    assertThat(store.findSchedule("Y")).isPresent();

    assertThat(JdbcAdminReportStore.normalizeCategory(null)).isNull();
    assertThat(JdbcAdminReportStore.normalizeCategory("  ")).isNull();
    assertThat(JdbcAdminReportStore.normalizeCategory("finance")).isEqualTo("FINANCE");
    assertThat(JdbcAdminReportStore.toTextArrayLiteral(null)).isEqualTo("{}");
    assertThat(JdbcAdminReportStore.toTextArrayLiteral(List.of())).isEqualTo("{}");
    assertThat(JdbcAdminReportStore.toTextArrayLiteral(List.of("a"))).contains("a");
    assertThat(JdbcAdminReportStore.toTextArrayLiteral(java.util.Arrays.asList("a", null, "b\"c")))
        .isEqualTo("{\"a\",\"\",\"b\\\"c\"}");
    assertThat(JdbcAdminReportStore.nz(null)).isEqualTo(0L);
    assertThat(JdbcAdminReportStore.nz(7L)).isEqualTo(7L);
    assertThat(JdbcAdminReportStore.nzInt(null)).isEqualTo(0);
    assertThat(JdbcAdminReportStore.nzInt(3)).isEqualTo(3);
    store.upsertSchedule(
        new ScheduleRow(id, "GMV_COMMISSION_PAYOUTS", false, "DAILY", "CSV", null, null, id, now));
    store.insertJob(
        new JobRow(
            id,
            "GMV_COMMISSION_PAYOUTS",
            id,
            "MANUAL",
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 31),
            "{}",
            "CSV",
            "COMPLETED",
            100,
            1,
            1,
            "k",
            "u",
            now,
            now,
            now,
            now,
            null));
    assertThat(store.listHistory("", now, 20, 0)).isNotEmpty();
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
    assertThat(store.countHistory("  ", now)).isEqualTo(0L);
    ReportDefinition d = new ReportDefinition("A", "n", "FINANCE", "d", "DAILY", "CSV", 2, true);
    assertThat(d.reportId()).isEqualTo("A");
    verify(jdbc, org.mockito.Mockito.atLeastOnce()).update(anyString(), any(Object[].class));
  }

  @Test
  void staticHelpersCoverNullBranches() throws Exception {
    assertThat(JdbcAdminReportStore.instantOrNull(null)).isNull();
    Instant now = Instant.parse("2026-07-24T01:30:00Z");
    assertThat(JdbcAdminReportStore.instantOrNull(Timestamp.from(now))).isEqualTo(now);
    assertThat(JdbcAdminReportStore.instantOrEpoch(null)).isEqualTo(Instant.EPOCH);
    assertThat(JdbcAdminReportStore.instantOrEpoch(Timestamp.from(now))).isEqualTo(now);
    assertThat(JdbcAdminReportStore.nullToEmpty(null)).isEmpty();
    assertThat(JdbcAdminReportStore.nullToEmpty("x")).isEqualTo("x");
    assertThat(JdbcAdminReportStore.emailsFromArray(null)).isEmpty();
    when(sqlArray.getArray()).thenReturn(new String[] {"a"});
    assertThat(JdbcAdminReportStore.emailsFromArray(sqlArray)).containsExactly("a");
    when(sqlArray.getArray()).thenReturn(new Object[] {1});
    assertThat(JdbcAdminReportStore.emailsFromArray(sqlArray)).containsExactly("1");
    when(sqlArray.getArray()).thenReturn("not-array");
    assertThat(JdbcAdminReportStore.emailsFromArray(sqlArray)).isEmpty();

    JdbcAdminReportStore store = new JdbcAdminReportStore(jdbc);
    when(rs.getString(anyString())).thenReturn("GMV");
    when(rs.getInt(anyString())).thenReturn(2);
    when(rs.getBoolean(anyString())).thenReturn(true);
    when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Object>>any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    assertThat(store.listDefinitions("  ")).isNotEmpty();
    when(jdbc.query(anyString(), ArgumentMatchers.<ResultSetExtractor<Instant>>any(), any()))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<Instant> ex = inv.getArgument(1);
              when(rs.next()).thenReturn(false);
              return ex.extractData(rs);
            });
    assertThat(store.lastCompletedAt("NONE")).isNull();
    store.insertJob(
        new JobRow(
            UUID.randomUUID(),
            "X",
            null,
            "MANUAL",
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 2),
            null,
            "CSV",
            "QUEUED",
            0,
            null,
            null,
            null,
            null,
            null,
            now,
            null,
            null,
            null));
  }
}
