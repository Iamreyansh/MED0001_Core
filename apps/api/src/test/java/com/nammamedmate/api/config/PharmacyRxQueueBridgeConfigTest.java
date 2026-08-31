package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.crm.application.port.out.CrmPlanLookupPort;
import com.nammamedmate.crm.domain.PlanNames;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.prescription.application.port.out.NotificationDispatchPort;
import com.nammamedmate.prescription.application.port.out.OrderLinesPort;
import com.nammamedmate.prescription.application.port.out.OrderStatusPort;
import com.nammamedmate.prescription.application.port.out.PharmacyPlanPort;
import com.nammamedmate.prescription.application.port.out.PosDispensePort;
import com.nammamedmate.prescription.domain.PharmacyRxQueueEntry.ApprovedMedicine;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PharmacyRxQueueBridgeConfigTest {

  private final PharmacyRxQueueBridgeConfig config = new PharmacyRxQueueBridgeConfig();
  private final Clock clock = Clock.fixed(Instant.parse("2026-07-24T07:30:00Z"), ZoneOffset.UTC);
  private final ObjectMapper om = new ObjectMapper().findAndRegisterModules();

  @Test
  void planPortUsesCrmStarterGate() {
    CrmPlanLookupPort lookup = mock(CrmPlanLookupPort.class);
    UUID pharmacyId = UUID.randomUUID();
    when(lookup.planNameForPharmacy(pharmacyId)).thenReturn(Optional.of(PlanNames.FREE));
    PharmacyPlanPort port = config.crmPharmacyPlanPort(lookup);
    assertThat(port.rxQueueEnabled(pharmacyId)).isFalse();
    when(lookup.planNameForPharmacy(pharmacyId)).thenReturn(Optional.of(PlanNames.STARTER));
    assertThat(port.rxQueueEnabled(pharmacyId)).isTrue();
    when(lookup.planNameForPharmacy(pharmacyId)).thenReturn(Optional.empty());
    assertThat(port.rxQueueEnabled(pharmacyId)).isFalse();
  }

  @Test
  void orderLinesAndStatusUpdateJdbc() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    OrderLinesPort lines = config.jdbcOrderLinesPort(jdbc, om, clock);
    lines.replaceOrderLines(null, List.of());
    UUID orderId = UUID.randomUUID();
    lines.replaceOrderLines(
        orderId, List.of(new ApprovedMedicine("Metformin", 2, new BigDecimal("10.50"))));
    verify(jdbc).update(anyString(), any(), any(), any(), any(), eq(orderId));

    OrderStatusPort status = config.jdbcOrderStatusPort(jdbc, clock);
    status.markReadyForPickup(null);
    status.markReadyForPickup(orderId);
    verify(jdbc).update(anyString(), any(), any(), eq(orderId));
  }

  @Test
  void posAndNotifications() {
    com.nammamedmate.pos.application.PosCartService carts =
        mock(com.nammamedmate.pos.application.PosCartService.class);
    com.nammamedmate.pos.application.port.out.ProductLookupPort products =
        mock(com.nammamedmate.pos.application.port.out.ProductLookupPort.class);
    when(carts.createCart(any(), any()))
        .thenReturn(Map.of("cart_id", UUID.randomUUID().toString()));
    PosDispensePort pos = config.posCartDispenseBridge(carts, products);
    assertThat(pos.available()).isTrue();
    assertThat(pos.pushToBillingCart(UUID.randomUUID(), UUID.randomUUID(), List.of())).isNotNull();
    assertThat(pos.createSaleRecord(UUID.randomUUID(), UUID.randomUUID(), null, List.of()))
        .isNotNull();

    OutboxPublisher outbox = mock(OutboxPublisher.class);
    NotificationDispatchPort n = config.outboxRxNotificationPort(outbox);
    n.notifyCustomerRxRejected(UUID.randomUUID(), UUID.randomUUID(), "ILLEGIBLE", "x");
    n.notifyPharmacyOwnerOverdue(UUID.randomUUID(), UUID.randomUUID());
    n.notifyComplianceOverdueAudit(UUID.randomUUID(), UUID.randomUUID());
    n.notifyHeadOfComplianceFlag(UUID.randomUUID(), "HIGH", "dup");
    n.notifyComplianceDoctorScheduleAlert(UUID.randomUUID(), 51L);
    n.notifyComplianceDoctorBlacklisted(UUID.randomUUID(), "fraud");
    n.notifyComplianceFilingOverdue(UUID.randomUUID(), "SCHEDULE_H1_REGISTER", false);
    n.notifyComplianceFilingOverdue(UUID.randomUUID(), "SCHEDULE_X_REGISTER", true);
    n.notifyPharmacyDrugRecall(UUID.randomUUID(), "Paracetamol 500mg", "PCM2024Q1");
    verify(outbox, org.mockito.Mockito.times(9)).publish(any());
  }
}
