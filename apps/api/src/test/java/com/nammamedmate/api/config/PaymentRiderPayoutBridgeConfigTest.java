package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.payment.application.port.out.CashfreePayoutPort;
import com.nammamedmate.payment.application.port.out.RiderPayoutNotificationPort;
import com.nammamedmate.payment.application.port.out.RiderPayoutPort;
import com.nammamedmate.rider.application.port.out.CashfreeRoutePort;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class PaymentRiderPayoutBridgeConfigTest {

  @Test
  void notificationAndCashfreeRouteBridges() {
    PaymentRiderPayoutBridgeConfig config = new PaymentRiderPayoutBridgeConfig();
    OutboxPublisher outbox = mock(OutboxPublisher.class);
    RiderPayoutNotificationPort notifications = config.riderPayoutNotificationBridge(outbox);
    UUID riderId = UUID.randomUUID();
    UUID payoutId = UUID.randomUUID();
    notifications.payoutReleased(riderId, payoutId, 100, "pout_1");
    notifications.payoutFailed(riderId, payoutId, "err");
    ArgumentCaptor<DomainEvent> events = ArgumentCaptor.forClass(DomainEvent.class);
    verify(outbox, org.mockito.Mockito.times(2)).publish(events.capture());
    assertThat(events.getAllValues().get(0).type()).isEqualTo("rider.notification.payout_released");
    assertThat(events.getAllValues().get(1).type()).isEqualTo("finance.alert.payout_failed");

    CashfreePayoutPort payment = mock(CashfreePayoutPort.class);
    when(payment.initiatePayout(any()))
        .thenReturn(new CashfreePayoutPort.PayoutResult("pout_1", 4));
    CashfreeRoutePort route = config.riderCashfreePayoutBridge(payment);
    assertThat(route.disburse(riderId, 500, payoutId).success()).isTrue();

    when(payment.initiatePayout(any()))
        .thenThrow(new AppException("CASHFREE_PAYOUT_FAILED", "down", 502));
    assertThat(route.disburse(riderId, 500, payoutId).success()).isFalse();
  }

  @Test
  void jdbcPortBeanCreated() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    RiderPayoutPort port = new PaymentRiderPayoutBridgeConfig().jdbcRiderPayoutPort(jdbc);
    assertThat(port).isNotNull();
    when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any()))
        .thenReturn(java.util.List.of());
    assertThat(port.findById(UUID.randomUUID())).isEmpty();
  }
}
