package com.nammamedmate.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.pharmacy.adapter.out.persistence.JdbcAdminNoteStore;
import com.nammamedmate.pharmacy.adapter.out.persistence.JdbcAdminPharmacyStore;
import com.nammamedmate.pharmacy.adapter.out.persistence.JdbcBulkActionJobStore;
import com.nammamedmate.pharmacy.adapter.out.persistence.JdbcPharmacyCallLogStore;
import com.nammamedmate.pharmacy.adapter.out.persistence.JdbcPharmacyNoticeStore;
import com.nammamedmate.pharmacy.application.port.out.AdminNoteStore.NoteRow;
import com.nammamedmate.pharmacy.application.port.out.BulkActionJobStore.JobRow;
import com.nammamedmate.pharmacy.application.port.out.PharmacyCallLogStore.CallLogRow;
import com.nammamedmate.pharmacy.application.port.out.PharmacyNoticeStore.NoticeRow;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class PharmacyActionsJdbcCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T00:00:00Z");
  private static final UUID PID = Ids.newId();
  private static final UUID JOB = Ids.newId();

  @Test
  void jdbcPharmacyNoticeStore() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcPharmacyNoticeStore store = new JdbcPharmacyNoticeStore(jdbc);
    UUID noticeId = Ids.newId();

    when(jdbc.queryForObject(any(String.class), eq(Integer.class), eq(PID), any(Timestamp.class)))
        .thenReturn(2);
    when(jdbc.query(any(String.class), any(RowMapper.class), eq(PID), any(Timestamp.class)))
        .thenReturn(List.of(NOW.minusSeconds(3000)));

    assertThat(store.countSince(PID, NOW.minusSeconds(3600))).isEqualTo(2);
    assertThat(store.oldestSentAtSince(PID, NOW.minusSeconds(3600)))
        .isEqualTo(NOW.minusSeconds(3000));

    store.insert(
        new NoticeRow(
            noticeId,
            PID,
            List.of("WHATSAPP", "IN_APP"),
            "sub",
            "msg",
            "PHARMACY_GENERAL_NOTICE",
            "NORMAL",
            Ids.newId(),
            NOW,
            JOB));

    when(jdbc.query(any(String.class), any(RowMapper.class), eq(noticeId)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(noticeRs(noticeId), 0));
            });
    assertThat(JdbcPharmacyNoticeStore.mapRow(noticeRs(noticeId), 0).id()).isEqualTo(noticeId);
    assertThat(JdbcPharmacyNoticeStore.readChannels(noticeRs(noticeId)))
        .containsExactly("WHATSAPP", "IN_APP");
  }

  @Test
  void jdbcAdminNoteStore() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcAdminNoteStore store = new JdbcAdminNoteStore(jdbc);
    UUID noteId = Ids.newId();

    when(jdbc.query(any(String.class), any(RowMapper.class), eq(PID), eq(20), eq(0)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(noteRs(noteId), 0));
            });
    when(jdbc.queryForObject(any(String.class), eq(Long.class), eq(PID))).thenReturn(1L);

    assertThat(store.list(PID, true, 20, 0)).hasSize(1);
    assertThat(store.count(PID, true)).isEqualTo(1);
    store.insert(new NoteRow(noteId, PID, "note", true, Ids.newId(), NOW));
    assertThat(JdbcAdminNoteStore.mapRow(noteRs(noteId), 0).id()).isEqualTo(noteId);
  }

  @Test
  void jdbcPharmacyCallLogStore() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcPharmacyCallLogStore store = new JdbcPharmacyCallLogStore(jdbc);
    UUID callId = Ids.newId();
    store.insert(new CallLogRow(callId, PID, 120, "RESOLVED", "notes", Ids.newId(), NOW));
    assertThat(JdbcPharmacyCallLogStore.mapRow(callRs(callId), 0).id()).isEqualTo(callId);
  }

  @Test
  void jdbcAdminNoteStoreWithoutFlagFilter() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcAdminNoteStore store = new JdbcAdminNoteStore(jdbc);
    when(jdbc.query(any(String.class), any(RowMapper.class), eq(PID), eq(20), eq(0)))
        .thenReturn(List.of());
    when(jdbc.queryForObject(any(String.class), eq(Long.class), eq(PID))).thenReturn(2L);
    assertThat(store.list(PID, null, 20, 0)).isEmpty();
    assertThat(store.count(PID, null)).isEqualTo(2L);
  }

  @Test
  void jdbcPharmacyNoticeStoreNullChannels() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getArray("channels")).thenReturn(null);
    assertThat(JdbcPharmacyNoticeStore.readChannels(rs)).isEmpty();
    Array array = mock(Array.class);
    when(array.getArray()).thenReturn(null);
    when(rs.getArray("channels")).thenReturn(array);
    assertThat(JdbcPharmacyNoticeStore.readChannels(rs)).isEmpty();
  }

  @Test
  void jdbcBulkActionJobStoreReadNullArrays() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcBulkActionJobStore store = new JdbcBulkActionJobStore(jdbc, mapper);
    when(jdbc.query(any(String.class), any(RowMapper.class), eq(JOB)))
        .thenAnswer(
            inv -> {
              RowMapper<?> rowMapper = inv.getArgument(1);
              ResultSet rs = jobRs(JOB);
              when(rs.getArray("pharmacy_ids")).thenReturn(null);
              return List.of(rowMapper.mapRow(rs, 0));
            });
    assertThat(store.findById(JOB).orElseThrow().pharmacyIds()).isEmpty();
  }

  @Test
  void jdbcAdminPharmacyStoreListByIdsEmpty() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcAdminPharmacyStore store = new JdbcAdminPharmacyStore(jdbc, new ObjectMapper());
    assertThat(store.listByIds(List.of())).isEmpty();
  }

  @Test
  void jdbcBulkActionJobStore() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ObjectMapper mapper = new ObjectMapper();
    JdbcBulkActionJobStore store = new JdbcBulkActionJobStore(jdbc, mapper);

    when(jdbc.query(any(String.class), any(RowMapper.class), eq(JOB)))
        .thenReturn(List.of())
        .thenAnswer(
            inv -> {
              RowMapper<?> rowMapper = inv.getArgument(1);
              return List.of(rowMapper.mapRow(jobRs(JOB), 0));
            });
    when(jdbc.query(any(String.class), any(RowMapper.class), eq(5)))
        .thenAnswer(
            inv -> {
              RowMapper<?> rowMapper = inv.getArgument(1);
              return List.of(rowMapper.mapRow(jobRs(JOB), 0));
            });

    assertThat(store.findById(JOB)).isEmpty();
    assertThat(store.findById(JOB)).isPresent();
    assertThat(store.findQueued(5)).hasSize(1);

    store.insert(
        new JobRow(
            JOB,
            "SEND_NOTICE",
            Map.of("message", "m"),
            List.of(PID),
            "QUEUED",
            1,
            0,
            0,
            0,
            0,
            List.of(),
            Map.of(),
            Ids.newId(),
            null,
            null,
            NOW));
    store.markRunning(JOB, NOW);
    store.updateProgress(JOB, 1, 1, 0, 0, List.of());
    store.markCompleted(JOB, 1, 1, 0, 0, List.of(), Map.of("ok", true), NOW);
  }

  @Test
  void jdbcAdminPharmacyStoreListByIdsNull() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcAdminPharmacyStore store = new JdbcAdminPharmacyStore(jdbc, new ObjectMapper());
    assertThat(store.listByIds(null)).isEmpty();
  }

  @Test
  void jdbcPharmacyNoticeStoreNullTimestamp() throws Exception {
    ResultSet rs = noticeRs(Ids.newId());
    when(rs.getTimestamp("sent_at")).thenReturn(null);
    assertThat(JdbcPharmacyNoticeStore.mapRow(rs, 0).sentAt()).isNull();
  }

  @Test
  void jdbcBulkActionJobStoreNullPayloadAndSkippedJson() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ObjectMapper mapper = new ObjectMapper();
    JdbcBulkActionJobStore store = new JdbcBulkActionJobStore(jdbc, mapper);
    store.insert(
        new JobRow(
            Ids.newId(),
            "EXPORT",
            null,
            List.of(PID),
            "QUEUED",
            1,
            0,
            0,
            0,
            0,
            null,
            null,
            Ids.newId(),
            null,
            null,
            NOW));

    UUID nullPayloadJob = Ids.newId();
    when(jdbc.query(any(String.class), any(RowMapper.class), eq(nullPayloadJob)))
        .thenAnswer(
            inv -> {
              RowMapper<?> rowMapper = inv.getArgument(1);
              ResultSet rs = jobRs(nullPayloadJob);
              when(rs.getString("payload")).thenReturn(null);
              when(rs.getString("skipped_pharmacies")).thenReturn(null);
              return List.of(rowMapper.mapRow(rs, 0));
            });
    assertThat(store.findById(nullPayloadJob).orElseThrow().payload()).isEmpty();

    UUID badSkipped = Ids.newId();
    when(jdbc.query(any(String.class), any(RowMapper.class), eq(badSkipped)))
        .thenAnswer(
            inv -> {
              RowMapper<?> rowMapper = inv.getArgument(1);
              ResultSet rs = jobRs(badSkipped);
              when(rs.getString("payload")).thenReturn("{}");
              when(rs.getString("skipped_pharmacies")).thenReturn("{bad");
              return List.of(rowMapper.mapRow(rs, 0));
            });
    assertThatThrownBy(() -> store.findById(badSkipped)).isInstanceOf(IllegalStateException.class);
  }

  private static ResultSet noticeRs(UUID id) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getObject("pharmacy_id")).thenReturn(PID);
    Array channels = mock(Array.class);
    when(channels.getArray()).thenReturn(new String[] {"WHATSAPP", "IN_APP"});
    when(rs.getArray("channels")).thenReturn(channels);
    when(rs.getString("subject")).thenReturn("sub");
    when(rs.getString("message")).thenReturn("msg");
    when(rs.getString("template_name")).thenReturn("PHARMACY_GENERAL_NOTICE");
    when(rs.getString("priority")).thenReturn("NORMAL");
    when(rs.getObject("sent_by")).thenReturn(Ids.newId());
    when(rs.getTimestamp("sent_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getObject("bulk_job_id")).thenReturn(JOB);
    return rs;
  }

  private static ResultSet noteRs(UUID id) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getObject("pharmacy_id")).thenReturn(PID);
    when(rs.getString("note")).thenReturn("note");
    when(rs.getBoolean("is_flagged")).thenReturn(true);
    when(rs.getObject("added_by")).thenReturn(Ids.newId());
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
    return rs;
  }

  private static ResultSet callRs(UUID id) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getObject("pharmacy_id")).thenReturn(PID);
    when(rs.getInt("duration_seconds")).thenReturn(120);
    when(rs.getString("call_outcome")).thenReturn("RESOLVED");
    when(rs.getString("notes")).thenReturn("n");
    when(rs.getObject("logged_by")).thenReturn(Ids.newId());
    when(rs.getTimestamp("logged_at")).thenReturn(Timestamp.from(NOW));
    return rs;
  }

  private static ResultSet jobRs(UUID id) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getString("action")).thenReturn("SEND_NOTICE");
    when(rs.getString("payload")).thenReturn("{\"message\":\"m\"}");
    Array pharmacyIds = mock(Array.class);
    when(pharmacyIds.getArray()).thenReturn(new UUID[] {PID});
    when(rs.getArray("pharmacy_ids")).thenReturn(pharmacyIds);
    when(rs.getString("status")).thenReturn("QUEUED");
    when(rs.getInt("total_pharmacies")).thenReturn(1);
    when(rs.getInt("processed")).thenReturn(0);
    when(rs.getInt("succeeded")).thenReturn(0);
    when(rs.getInt("failed")).thenReturn(0);
    when(rs.getInt("skipped")).thenReturn(0);
    when(rs.getString("skipped_pharmacies")).thenReturn(null);
    when(rs.getString("result_payload")).thenReturn(null);
    when(rs.getObject("initiated_by")).thenReturn(Ids.newId());
    when(rs.getTimestamp("started_at")).thenReturn(null);
    when(rs.getTimestamp("completed_at")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
    return rs;
  }
}
