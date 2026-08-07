package com.nammamedmate.catalogue.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.catalogue.application.port.out.MedicineBanJobStore.BanJobRow;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
class JdbcMedicineBanJobStoreTest {

  private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");

  @Mock private JdbcTemplate jdbc;
  private JdbcMedicineBanJobStore store;

  @BeforeEach
  void setUp() {
    store = new JdbcMedicineBanJobStore(jdbc);
  }

  @Test
  void insertRunningCompletedAndFind() throws Exception {
    UUID jobId = UUID.randomUUID();
    UUID medicineId = UUID.randomUUID();
    UUID adminId = UUID.randomUUID();

    store.insertQueued(jobId, medicineId, "ban", adminId, NOW);
    verify(jdbc)
        .update(
            anyString(),
            eq(jobId),
            eq(medicineId),
            eq("ban"),
            eq(adminId),
            eq(Timestamp.from(NOW)));

    store.markRunning(jobId, NOW);
    verify(jdbc).update(anyString(), eq(Timestamp.from(NOW)), eq(jobId));

    store.markCompleted(jobId, 4, NOW);
    verify(jdbc).update(anyString(), eq(4), eq(Timestamp.from(NOW)), eq(jobId));

    when(jdbc.query(anyString(), any(RowMapper.class), eq(jobId)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(jobId);
              when(rs.getObject("medicine_id")).thenReturn(medicineId);
              when(rs.getString("status")).thenReturn("COMPLETED");
              when(rs.getInt("mappings_hidden")).thenReturn(4);
              when(rs.getString("reason")).thenReturn("ban");
              when(rs.getObject("initiated_by")).thenReturn(adminId);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("started_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("completed_at")).thenReturn(Timestamp.from(NOW));
              return List.of(mapper.mapRow(rs, 0));
            });
    BanJobRow row = store.findById(jobId).orElseThrow();
    assertThat(row.status()).isEqualTo("COMPLETED");
    assertThat(row.mappingsHidden()).isEqualTo(4);
    assertThat(row.startedAt()).isEqualTo(NOW);
    assertThat(row.completedAt()).isEqualTo(NOW);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(jobId)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(jobId);
              when(rs.getObject("medicine_id")).thenReturn(medicineId);
              when(rs.getString("status")).thenReturn("QUEUED");
              when(rs.getInt("mappings_hidden")).thenReturn(0);
              when(rs.getString("reason")).thenReturn("ban");
              when(rs.getObject("initiated_by")).thenReturn(adminId);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("started_at")).thenReturn(null);
              when(rs.getTimestamp("completed_at")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    BanJobRow queued = store.findById(jobId).orElseThrow();
    assertThat(queued.startedAt()).isNull();
    assertThat(queued.completedAt()).isNull();

    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
    assertThat(store.findById(UUID.randomUUID())).isEmpty();
  }
}
