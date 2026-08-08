package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.order.application.port.out.ZoneMembershipPort;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class OrderZoneBridgeConfigTest {

  @Test
  @SuppressWarnings("unchecked")
  void zoneMembershipBridge() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ZoneMembershipPort port = new OrderZoneBridgeConfig().jdbcZoneMembershipPort(jdbc);
    UUID pharmacyId = UUID.randomUUID();

    assertThat(port.isInPharmacyZone(null, 12.9, 77.6)).isFalse();
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(), any(), any())).thenReturn(true);
    assertThat(port.isInPharmacyZone(pharmacyId, 12.93, 77.62)).isTrue();
    // AC-002 / AC-003: outside polygon or is_serviceable=false → not serviceable
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(), any(), any()))
        .thenReturn(false);
    assertThat(port.isInPharmacyZone(pharmacyId, 12.0, 77.0)).isFalse();

    assertThat(port.minOrderValuePaise(null, 12.9, 77.6)).isEmpty();
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenReturn(List.of(new BigDecimal("50.00")));
    assertThat(port.minOrderValuePaise(pharmacyId, 12.93, 77.62)).hasValue(5000L);

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any())).thenReturn(List.of());
    assertThat(port.minOrderValuePaise(pharmacyId, 12.0, 77.0)).isEmpty();
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenReturn(java.util.Collections.singletonList(null));
    assertThat(port.minOrderValuePaise(pharmacyId, 12.0, 77.0)).isEmpty();
  }
}
