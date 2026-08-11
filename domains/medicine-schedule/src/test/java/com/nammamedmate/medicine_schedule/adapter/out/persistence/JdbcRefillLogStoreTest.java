package com.nammamedmate.medicine_schedule.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.medicine_schedule.application.port.out.RefillLogStore.RefillLogRecord;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcRefillLogStoreTest {

  @Test
  void insertAndExistsNegative() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcRefillLogStore store = new JdbcRefillLogStore(jdbc);
    UUID medicineId = Ids.newId();
    store.insert(
        new RefillLogRecord(
            Ids.newId(),
            medicineId,
            Ids.newId(),
            -2,
            5,
            3,
            LocalDate.of(2026, 7, 24),
            Instant.parse("2026-07-24T07:00:00Z")));
    verify(jdbc).update(anyString(), any(), any(), any(), any(), any(), any(), any(), any());
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(medicineId), any())).thenReturn(1);
    assertThat(store.existsNegativeOnDate(medicineId, LocalDate.of(2026, 7, 24))).isTrue();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(medicineId), any())).thenReturn(0);
    assertThat(store.existsNegativeOnDate(medicineId, LocalDate.of(2026, 7, 24))).isFalse();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(medicineId), any()))
        .thenReturn(null);
    assertThat(store.existsNegativeOnDate(medicineId, LocalDate.of(2026, 7, 24))).isFalse();
  }
}
