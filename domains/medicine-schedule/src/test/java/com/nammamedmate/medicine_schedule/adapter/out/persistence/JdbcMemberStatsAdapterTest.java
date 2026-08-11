package com.nammamedmate.medicine_schedule.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.medicine_schedule.application.port.out.MemberStatsPort.MemberListStats;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class JdbcMemberStatsAdapterTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-24T06:30:00Z"), ZoneOffset.UTC);

  @Test
  void statsForMember() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcMemberStatsAdapter adapter = new JdbcMemberStatsAdapter(jdbc, CLOCK);
    UUID memberId = Ids.newId();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(memberId)))
        .thenReturn(3)
        .thenReturn(1);
    when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(memberId), any()))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<int[]> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(true);
              when(rs.getInt("total")).thenReturn(5);
              when(rs.getInt("taken")).thenReturn(4);
              return ex.extractData(rs);
            });

    MemberListStats stats = adapter.statsForMember(memberId);
    assertThat(stats.medicinesCount()).isEqualTo(3);
    assertThat(stats.refillAlertsCount()).isEqualTo(1);
    assertThat(stats.todayDosesTotal()).isEqualTo(5);
    assertThat(stats.todayDosesTaken()).isEqualTo(4);
    assertThat(stats.todayAdherencePct()).isEqualTo(80.0);
  }

  @Test
  void nullCountsBecomeZero() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcMemberStatsAdapter adapter = new JdbcMemberStatsAdapter(jdbc, CLOCK);
    UUID memberId = Ids.newId();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(memberId))).thenReturn(null);
    when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(memberId), any()))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<int[]> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(false);
              return ex.extractData(rs);
            });

    MemberListStats stats = adapter.statsForMember(memberId);
    assertThat(stats.medicinesCount()).isZero();
    assertThat(stats.refillAlertsCount()).isZero();
    assertThat(stats.todayDosesTotal()).isZero();
    assertThat(stats.todayAdherencePct()).isNull();
  }

  @Test
  void nullTodayCountsArray() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcMemberStatsAdapter adapter = new JdbcMemberStatsAdapter(jdbc, CLOCK);
    UUID memberId = Ids.newId();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(memberId))).thenReturn(null);
    when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(memberId), any()))
        .thenReturn(null);
    MemberListStats stats = adapter.statsForMember(memberId);
    assertThat(stats.todayDosesTotal()).isZero();
  }
}
