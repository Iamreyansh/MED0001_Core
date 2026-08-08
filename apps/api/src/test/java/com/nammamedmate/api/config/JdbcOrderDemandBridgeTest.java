package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcOrderDemandBridgeTest {

  @Test
  void trailing30DayOrderCount() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcOrderDemandBridge bridge = new JdbcOrderDemandBridge(jdbc);
    assertThat(bridge.trailing30DayOrderCount(null)).isZero();

    UUID med = UUID.randomUUID();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(7);
    assertThat(bridge.trailing30DayOrderCount(med)).isEqualTo(7);

    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(null);
    assertThat(bridge.trailing30DayOrderCount(med)).isZero();
  }
}
