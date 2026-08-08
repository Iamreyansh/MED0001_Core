package com.nammamedmate.rider.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.rider.adapter.out.persistence.JdbcDispatchOrderAdapter;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcDispatchMapCoverageTest {

  @Test
  @SuppressWarnings("unchecked")
  void mapQueueCoversNullBlankAndReadyTimestampBranches() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(3);
    AtomicInteger n = new AtomicInteger();
    Instant now = Instant.parse("2026-07-24T09:00:00Z");
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              int i = n.getAndIncrement() % 3;
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(Ids.newId());
              when(rs.getString("order_number")).thenReturn("M");
              when(rs.getObject("pharmacy_id")).thenReturn(Ids.newId());
              when(rs.getString("business_name"))
                  .thenReturn(i == 0 ? null : (i == 1 ? "  " : "Apollo"));
              when(rs.getString("pharmacy_fallback")).thenReturn("Fallback");
              when(rs.getObject("zone_id")).thenReturn(Ids.newId());
              when(rs.getString("zone_name")).thenReturn("Z");
              when(rs.getInt("items_count")).thenReturn(1);
              when(rs.getLong("total_payable_paise")).thenReturn(1L);
              when(rs.getString("payment_method")).thenReturn("UPI");
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("ready_for_pickup_at"))
                  .thenReturn(i == 2 ? null : Timestamp.from(now));
              when(rs.getObject("latitude")).thenReturn(1.0);
              when(rs.getObject("longitude")).thenReturn(2.0);
              return List.of(mapper.mapRow(rs, 0));
            });
    JdbcDispatchOrderAdapter adapter = new JdbcDispatchOrderAdapter(jdbc);
    UUID zone = Ids.newId();
    assertThat(adapter.listUnassignedReady(zone, 1, 10).rows()).hasSize(1);
    assertThat(adapter.listUnassignedReady(zone, 1, 10).rows()).hasSize(1);
    assertThat(adapter.listUnassignedReady(zone, 1, 10).rows()).hasSize(1);
  }
}
