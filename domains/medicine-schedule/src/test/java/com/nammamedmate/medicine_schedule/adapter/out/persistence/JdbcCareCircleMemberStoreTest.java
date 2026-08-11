package com.nammamedmate.medicine_schedule.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.medicine_schedule.application.port.out.CareCircleMemberStore.MemberRecord;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcCareCircleMemberStoreTest {

  private static final Instant NOW = Instant.parse("2026-07-24T07:00:00Z");

  @Test
  @SuppressWarnings("unchecked")
  void listAndFindMapRows() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCareCircleMemberStore store = new JdbcCareCircleMemberStore(jdbc);
    MemberRecord record = sample(false);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(record.customerId())))
        .thenAnswer(
            inv -> {
              RowMapper<MemberRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockRs(record, false), 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class), eq(record.id())))
        .thenAnswer(
            inv -> {
              RowMapper<MemberRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockRs(record, true), 0));
            });

    assertThat(store.listByCustomer(record.customerId())).containsExactly(record);
    assertThat(store.findById(record.id())).isPresent();
  }

  @Test
  void countAndNull() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCareCircleMemberStore store = new JdbcCareCircleMemberStore(jdbc);
    UUID customerId = Ids.newId();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(customerId))).thenReturn(2);
    assertThat(store.countByCustomer(customerId)).isEqualTo(2);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(UUID.class))).thenReturn(null);
    assertThat(store.countByCustomer(Ids.newId())).isZero();
  }

  @Test
  @SuppressWarnings("unchecked")
  void findSelfEmptyAndInsertUpdateDelete() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCareCircleMemberStore store = new JdbcCareCircleMemberStore(jdbc);
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
    assertThat(store.findSelf(Ids.newId())).isEmpty();

    MemberRecord record = sample(true);
    store.insert(record);
    store.update(record);
    store.softDelete(record.id(), NOW);
    verify(jdbc)
        .update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(jdbc).update(anyString(), any(), any(), any(), any(), any(), any(), any());
    verify(jdbc).update(anyString(), any(), any(), eq(record.id()));
  }

  private static MemberRecord sample(boolean self) {
    return new MemberRecord(
        Ids.newId(),
        Ids.newId(),
        "Name",
        30,
        self ? "SELF" : "PARENT",
        "👤",
        "#6B7280",
        self,
        NOW,
        NOW,
        null);
  }

  private static ResultSet mockRs(MemberRecord r, boolean withDeleted) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(r.id());
    when(rs.getObject("customer_id")).thenReturn(r.customerId());
    when(rs.getString("name")).thenReturn(r.name());
    when(rs.getInt("age")).thenReturn(r.age());
    when(rs.getString("relationship")).thenReturn(r.relationship());
    when(rs.getString("avatar_emoji")).thenReturn(r.avatarEmoji());
    when(rs.getString("avatar_color")).thenReturn(r.avatarColor());
    when(rs.getBoolean("is_self")).thenReturn(r.self());
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(r.createdAt()));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(r.updatedAt()));
    when(rs.getTimestamp("deleted_at")).thenReturn(withDeleted ? Timestamp.from(NOW) : null);
    return rs;
  }
}
