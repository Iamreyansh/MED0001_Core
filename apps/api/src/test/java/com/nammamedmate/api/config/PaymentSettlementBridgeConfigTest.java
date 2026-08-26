package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.payment.application.port.out.CashfreePayoutPort;
import com.nammamedmate.payment.application.port.out.PharmacySettlementPort;
import com.nammamedmate.payment.application.port.out.SettlementNotificationPort;
import com.nammamedmate.pharmacy.application.port.out.NotificationDispatchPort;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PaymentSettlementBridgeConfigTest {

  @Test
  void notificationAndCashfreePayoutBridges() {
    PaymentSettlementBridgeConfig config = new PaymentSettlementBridgeConfig();
    NotificationDispatchPort notifications = mock(NotificationDispatchPort.class);
    SettlementNotificationPort bridge = config.settlementNotificationBridge(notifications);
    UUID pharmacyId = UUID.randomUUID();
    UUID settlementId = UUID.randomUUID();
    bridge.settlementReleased(pharmacyId, settlementId, 100);
    bridge.settlementHeld(pharmacyId, settlementId, "reason");
    verify(notifications).dispatchSettlementReleased(pharmacyId, settlementId, 100);
    verify(notifications).dispatchSettlementHeld(pharmacyId, settlementId, "reason");

    CashfreePayoutPort payment = mock(CashfreePayoutPort.class);
    when(payment.initiatePayout(any()))
        .thenReturn(new CashfreePayoutPort.PayoutResult("pout_1", 4));
    var pharmacyPort = config.pharmacyCashfreePayoutBridge(payment);
    var result =
        pharmacyPort.initiatePayout(
            new com.nammamedmate.pharmacy.application.port.out.CashfreePayoutPort.PayoutRequest(
                pharmacyId, settlementId, 500, "4521", "HDFC0001"));
    assertThat(result.cashfreeTransferId()).isEqualTo("pout_1");
    assertThat(result.estimatedCreditHours()).isEqualTo(4);
  }

  @Test
  void jdbcPortBeanCreated() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    PharmacySettlementPort port =
        new PaymentSettlementBridgeConfig().jdbcPharmacySettlementPort(jdbc);
    assertThat(port).isNotNull();
    when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any()))
        .thenReturn(java.util.List.of());
    assertThat(port.findById(UUID.randomUUID())).isEmpty();
  }
}
