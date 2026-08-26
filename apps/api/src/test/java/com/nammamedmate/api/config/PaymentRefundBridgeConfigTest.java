package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.payment.application.port.out.RefundFinancePort;
import com.nammamedmate.payment.application.port.out.RefundNotificationPort;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PaymentRefundBridgeConfigTest {

  @Test
  void notificationBridgePublishesOutbox() {
    PaymentRefundBridgeConfig config = new PaymentRefundBridgeConfig();
    OutboxPublisher outbox = mock(OutboxPublisher.class);
    RefundNotificationPort bridge = config.refundNotificationBridge(outbox);
    UUID customerId = UUID.randomUUID();
    UUID refundId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    bridge.refundCompleted(customerId, refundId, orderId, 1000L);
    verify(outbox).publish(any());
  }

  @Test
  void jdbcPortBeanCreated() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    RefundFinancePort port = new PaymentRefundBridgeConfig().jdbcRefundFinancePort(jdbc);
    assertThat(port).isNotNull();
    when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any()))
        .thenReturn(java.util.List.of());
    assertThat(port.findById(UUID.randomUUID())).isEmpty();
    assertThat(port.findByGatewayRefundId("rfnd_x")).isEmpty();
  }
}
