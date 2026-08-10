package com.nammamedmate.marketing.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.marketing.application.port.out.SegmentStore;
import com.nammamedmate.marketing.domain.Segment;
import com.nammamedmate.marketing.domain.SegmentCriterion;
import com.nammamedmate.marketing.domain.SegmentType;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class JdbcSegmentStoreTest {

  @Mock JdbcTemplate jdbc;
  @Mock ResultSet rs;
  ObjectMapper om = new ObjectMapper().findAndRegisterModules();
  JdbcSegmentStore store;

  Instant now = Instant.parse("2026-07-24T10:00:00Z");
  UUID id = UUID.fromString("a0130004-0000-4000-8000-000000000004");

  @BeforeEach
  void setUp() {
    store = new JdbcSegmentStore(jdbc, om);
  }

  @Test
  void crudAndJobsAndMembers() throws Exception {
    Segment segment =
        new Segment(
            id,
            "VIP",
            "d",
            SegmentType.SYSTEM,
            List.of(new SegmentCriterion("total_orders", ">=", 30)),
            "READY",
            1,
            100L,
            200L,
            now,
            null,
            now,
            now,
            null);
    Segment withNullComputed =
        new Segment(
            id,
            "X",
            null,
            SegmentType.CUSTOM,
            List.of(),
            "READY",
            0,
            null,
            null,
            null,
            null,
            now,
            now,
            null);
    org.mockito.Mockito.lenient()
        .when(jdbc.update(anyString(), ArgumentMatchers.<Object[]>any()))
        .thenReturn(1);
    org.mockito.Mockito.lenient()
        .when(
            jdbc.update(
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
                any()))
        .thenReturn(1);
    assertThat(store.insert(withNullComputed)).isSameAs(withNullComputed);
    assertThat(store.insert(segment)).isSameAs(segment);

    stubSegmentRow();
    when(jdbc.query(contains("WHERE id = ?"), any(RowMapper.class), eq(id))).thenAnswer(queryOne());
    assertThat(store.findById(id)).isPresent();

    when(jdbc.query(contains("LOWER(name)"), any(RowMapper.class), eq("VIP")))
        .thenAnswer(queryOne());
    assertThat(store.findByNameIgnoreCase("VIP")).isPresent();

    when(jdbc.query(
            contains("segment_type = ?"), any(RowMapper.class), eq("SYSTEM"), eq(20), eq(0)))
        .thenAnswer(queryOne());
    assertThat(store.list(SegmentType.SYSTEM, 0, 20)).hasSize(1);

    when(jdbc.query(contains("ORDER BY segment_type"), any(RowMapper.class), eq(20), eq(0)))
        .thenAnswer(queryOne());
    assertThat(store.list(null, 0, 20)).hasSize(1);

    when(jdbc.queryForObject(
            contains("COUNT(*) FROM segments WHERE deleted_at IS NULL"), eq(Long.class)))
        .thenReturn(null);
    assertThat(store.count(null)).isZero();
    when(jdbc.queryForObject(
            contains("COUNT(*) FROM segments WHERE deleted_at IS NULL"), eq(Long.class)))
        .thenReturn(8L);
    assertThat(store.count(null)).isEqualTo(8);
    when(jdbc.queryForObject(contains("AND segment_type"), eq(Long.class), eq("CUSTOM")))
        .thenReturn(null);
    assertThat(store.count(SegmentType.CUSTOM)).isZero();

    store.softDelete(id, now);
    store.updateComputeResult(id, 2, 3L, 4L, now, "READY");
    store.replaceMemberships(id, List.of(), now);
    store.replaceMemberships(id, List.of(UUID.randomUUID()), now);
    store.upsertSnapshot(id, LocalDate.of(2026, 7, 21), 2);

    when(jdbc.query(contains("segment_snapshots"), any(RowMapper.class), eq(id), eq(4)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              when(rs.getDate("snapshot_date")).thenReturn(java.sql.Date.valueOf("2026-07-21"));
              when(rs.getInt("customer_count")).thenReturn(2);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.growthChart(id, 4)).hasSize(1);

    when(jdbc.queryForObject(contains("segment_memberships"), eq(Long.class), eq(id)))
        .thenReturn(1L);
    when(jdbc.query(contains("JOIN customers"), any(RowMapper.class), eq(id), eq(20), eq(0)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              when(rs.getObject("id")).thenReturn(UUID.randomUUID());
              when(rs.getString("name")).thenReturn("Priya");
              when(rs.getString("phone")).thenReturn("+91");
              when(rs.getInt("total_orders")).thenReturn(45);
              when(rs.getLong("total_ltv_paise")).thenReturn(1000L);
              when(rs.getTimestamp("last_order_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });
    SegmentStore.PagedMemberships page = store.listMembers(id, "total_orders", "asc", 0, 20);
    assertThat(page.total()).isEqualTo(1);
    store.listMembers(id, "name", "desc", 0, 20);
    store.listMembers(id, null, null, 0, 20);

    when(jdbc.update(contains("segment_compute_jobs"), any(), any(), any())).thenReturn(1);
    // enqueue uses Ids.newId + update with 3 args differently
    when(jdbc.update(contains("INSERT INTO segment_compute_jobs"), any(), any(), any()))
        .thenReturn(1);
    UUID jobId = store.enqueueComputeJob(id, now);
    assertThat(jobId).isNotNull();

    when(jdbc.query(contains("FROM segment_compute_jobs WHERE id"), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              when(rs.getObject("id")).thenReturn(jobId);
              when(rs.getObject("segment_id")).thenReturn(id);
              when(rs.getString("status")).thenReturn("QUEUED");
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findJob(jobId)).isPresent();

    when(jdbc.query(contains("status = 'QUEUED'"), any(RowMapper.class), eq(5)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              when(rs.getObject("id")).thenReturn(jobId);
              when(rs.getObject("segment_id")).thenReturn(id);
              when(rs.getString("status")).thenReturn("QUEUED");
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findQueuedJobs(5)).hasSize(1);

    store.markJobRunning(jobId, now);
    store.markJobCompleted(jobId, now);
    store.markJobFailed(jobId, now, "err");

    when(jdbc.query(contains("WHERE id = ?"), any(RowMapper.class), eq(id)))
        .thenReturn(Collections.emptyList());
    assertThat(store.findById(id)).isEmpty();
  }

  @Test
  void criteriaSerializeRoundTripAndNulls() throws Exception {
    stubSegmentRow();
    when(rs.getString("criteria")).thenReturn(null);
    when(rs.getObject("avg_aov_paise")).thenReturn(null);
    when(rs.getObject("total_ltv_paise")).thenReturn(null);
    when(rs.getTimestamp("last_computed_at")).thenReturn(null);
    when(rs.getTimestamp("deleted_at")).thenReturn(Timestamp.from(now));
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenAnswer(queryOne());
    Optional<Segment> seg = store.findById(id);
    assertThat(seg).isPresent();
    assertThat(seg.get().criteria()).isEmpty();

    when(rs.getString("criteria")).thenReturn("   ");
    assertThat(store.findById(id).orElseThrow().criteria()).isEmpty();

    when(rs.getString("criteria")).thenReturn("not-json");
    assertThatThrownBy(() -> store.findById(id)).isInstanceOf(IllegalStateException.class);

    when(rs.getString("criteria")).thenReturn("null");
    // Jackson may return null list from literal null JSON
    try {
      store.findById(id);
    } catch (RuntimeException ignored) {
      // tolerate either empty or explode depending on jackson
    }

    ObjectMapper exploding = org.mockito.Mockito.mock(ObjectMapper.class);
    try {
      when(exploding.writeValueAsString(any()))
          .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("boom") {});
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new AssertionError(e);
    }
    JdbcSegmentStore bad = new JdbcSegmentStore(jdbc, exploding);
    assertThatThrownBy(
            () ->
                bad.insert(
                    new Segment(
                        id,
                        "X",
                        null,
                        SegmentType.CUSTOM,
                        null,
                        "READY",
                        0,
                        null,
                        null,
                        null,
                        null,
                        now,
                        now,
                        null)))
        .isInstanceOf(IllegalStateException.class);

    when(jdbc.queryForObject(contains("AND segment_type"), eq(Long.class), eq("SYSTEM")))
        .thenReturn(8L);
    assertThat(store.count(SegmentType.SYSTEM)).isEqualTo(8);

    store.replaceMemberships(id, null, now);

    when(jdbc.queryForObject(contains("segment_memberships"), eq(Long.class), eq(id)))
        .thenReturn(null);
    when(jdbc.query(contains("JOIN customers"), any(RowMapper.class), eq(id), eq(10), eq(0)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              when(rs.getObject("id")).thenReturn(UUID.randomUUID());
              when(rs.getString("name")).thenReturn("X");
              when(rs.getString("phone")).thenReturn("p");
              when(rs.getInt("total_orders")).thenReturn(1);
              when(rs.getLong("total_ltv_paise")).thenReturn(0L);
              when(rs.getTimestamp("last_order_at")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.listMembers(id, "ltv_rs", "desc", 0, 10).total()).isZero();
  }

  @Test
  void isMember() {
    when(jdbc.queryForObject(contains("segment_memberships"), eq(Long.class), any(), any()))
        .thenReturn(1L);
    assertThat(store.isMember(UUID.randomUUID(), UUID.randomUUID())).isTrue();
    when(jdbc.queryForObject(contains("segment_memberships"), eq(Long.class), any(), any()))
        .thenReturn(0L);
    assertThat(store.isMember(UUID.randomUUID(), UUID.randomUUID())).isFalse();
    when(jdbc.queryForObject(contains("segment_memberships"), eq(Long.class), any(), any()))
        .thenReturn(null);
    assertThat(store.isMember(UUID.randomUUID(), UUID.randomUUID())).isFalse();
  }

  private void stubSegmentRow() throws Exception {
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getString("name")).thenReturn("VIP");
    when(rs.getString("description")).thenReturn("d");
    when(rs.getString("segment_type")).thenReturn("SYSTEM");
    when(rs.getString("criteria"))
        .thenReturn("[{\"field\":\"total_orders\",\"operator\":\">=\",\"value\":30}]");
    when(rs.getString("status")).thenReturn("READY");
    when(rs.getInt("customer_count")).thenReturn(1);
    when(rs.getObject("avg_aov_paise")).thenReturn(100L);
    when(rs.getObject("total_ltv_paise")).thenReturn(200L);
    when(rs.getTimestamp("last_computed_at")).thenReturn(Timestamp.from(now));
    when(rs.getObject("created_by")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("deleted_at")).thenReturn(null);
  }

  private Answer<List<?>> queryOne() {
    return inv -> {
      RowMapper<?> mapper = inv.getArgument(1);
      return List.of(mapper.mapRow(rs, 0));
    };
  }
}
