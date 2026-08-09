package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.payment.application.CodFloatFacadeService;
import com.nammamedmate.payment.application.port.out.CodFloatAlertPort;
import com.nammamedmate.payment.application.port.out.CodFloatPort;
import com.nammamedmate.rider.application.port.out.CodDepositConfirmedPort;
import com.nammamedmate.rider.application.port.out.FinanceCodDailyReconciliationPort;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class PaymentCodFloatBridgeConfigTest {

  @Test
  void alertAndFacadeBridges() {
    PaymentCodFloatBridgeConfig config = new PaymentCodFloatBridgeConfig();
    OutboxPublisher outbox = mock(OutboxPublisher.class);
    CodFloatAlertPort alert = config.codFloatAlertBridge(outbox);
    UUID reportId = UUID.randomUUID();
    alert.varianceAlert(reportId, LocalDate.parse("2026-07-24"), 15_000L, "DISCREPANCY");
    verify(outbox).publish(any());

    CodFloatFacadeService facade = mock(CodFloatFacadeService.class);
    CodDepositConfirmedPort deposit = config.codDepositLedgerBridge(facade);
    UUID depositId = UUID.randomUUID();
    UUID riderId = UUID.randomUUID();
    deposit.onDepositConfirmed(depositId, riderId, 100);
    verify(facade).onDepositConfirmed(depositId, riderId, 100);

    FinanceCodDailyReconciliationPort daily = config.financeCodDailyBridge(facade);
    daily.runForDate(LocalDate.parse("2026-07-24"));
    verify(facade).runScheduledReconciliation(LocalDate.parse("2026-07-24"));
  }

  @Test
  void jdbcPortBeanBasics() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    CodFloatPort port = new PaymentCodFloatBridgeConfig().jdbcCodFloatPort(jdbc);
    assertThat(port).isNotNull();

    when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of());
    assertThat(port.floatLimitPaise()).isEqualTo(200_000L);
    assertThat(port.findReport(LocalDate.now())).isEmpty();

    when(jdbc.queryForObject(anyString(), any(Class.class), any(Object[].class))).thenReturn(0L);
    assertThat(port.hasCodDepositLedgerEntry(UUID.randomUUID())).isFalse();
  }
}
