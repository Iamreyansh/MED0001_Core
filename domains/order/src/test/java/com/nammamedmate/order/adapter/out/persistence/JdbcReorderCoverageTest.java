package com.nammamedmate.order.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.order.application.port.out.ReorderAttemptLogStore.ReorderAttemptLog;
import com.nammamedmate.order.domain.Order;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
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
class JdbcReorderCoverageTest {

  @Mock private JdbcTemplate jdbc;

  @Test
  void reorderLogInsertAndHistoryQueries() {
    JdbcReorderAttemptLogStore logs = new JdbcReorderAttemptLogStore(jdbc);
    Instant now = Instant.parse("2026-08-08T12:00:00Z");
    UUID id = UUID.randomUUID();
    logs.insert(new ReorderAttemptLog(id, id, id, id, true, 3, 2, 1, now));
    verify(jdbc).update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any());

    JdbcOrderStore orders = new JdbcOrderStore(jdbc, new ObjectMapper());
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
        .thenReturn(Collections.emptyList());
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenReturn(Collections.emptyList());
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(Collections.emptyList());
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(2L);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(null);

    List<Order> filtered = orders.listCustomerHistory(id, "DELIVERED", 0, 20);
    assertThat(filtered).isEmpty();
    assertThat(orders.listCustomerHistory(id, "CANCELLED", 0, 10)).isEmpty();
    List<Order> all = orders.listCustomerHistory(id, "ALL", 0, 20);
    assertThat(all).isEmpty();
    assertThat(orders.listCustomerHistory(id, null, 0, 20)).isEmpty();
    assertThat(orders.countCustomerHistory(id, "CANCELLED")).isEqualTo(2L);
    assertThat(orders.countCustomerHistory(id, "DELIVERED")).isEqualTo(2L);
    assertThat(orders.countCustomerHistory(id, "ALL")).isEqualTo(0L);
    assertThat(orders.countCustomerHistory(id, null)).isEqualTo(0L);
    assertThat(orders.listCustomerActive(id)).isEmpty();
  }
}
