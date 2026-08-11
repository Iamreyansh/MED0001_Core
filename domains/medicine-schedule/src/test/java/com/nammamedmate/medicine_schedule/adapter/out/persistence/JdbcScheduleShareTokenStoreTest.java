package com.nammamedmate.medicine_schedule.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.medicine_schedule.application.port.out.ScheduleShareTokenStore.ScheduleShareTokenRecord;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcScheduleShareTokenStoreTest {

  @Test
  @SuppressWarnings("unchecked")
  void insertAndFind() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcScheduleShareTokenStore store = new JdbcScheduleShareTokenStore(jdbc);
    Instant now = Instant.parse("2026-07-24T07:00:00Z");
    ScheduleShareTokenRecord record =
        new ScheduleShareTokenRecord(
            Ids.newId(), "tok123", Ids.newId(), Ids.newId(), now.plusSeconds(3600), now);
    store.insert(record);
    verify(jdbc).update(anyString(), any(), any(), any(), any(), any(), any());

    when(jdbc.query(anyString(), any(RowMapper.class), eq("tok123")))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(record.id());
              when(rs.getString("token")).thenReturn(record.token());
              when(rs.getObject("customer_id")).thenReturn(record.customerId());
              when(rs.getObject("member_id")).thenReturn(record.memberId());
              when(rs.getTimestamp("expires_at")).thenReturn(Timestamp.from(record.expiresAt()));
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(record.createdAt()));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findByToken("tok123")).contains(record);
    when(jdbc.query(anyString(), any(RowMapper.class), eq("missing"))).thenReturn(List.of());
    assertThat(store.findByToken("missing")).isEmpty();
  }
}
