package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.prescription.application.EPrescriptionService;
import com.nammamedmate.prescription.application.EPrescriptionService.Created;
import com.nammamedmate.prescription.application.port.out.OrderLinkPort;
import com.nammamedmate.prescription.domain.EPrescriptionSignature.MedicinePrescribed;
import com.nammamedmate.teleconsult.application.port.out.CartLinkPort;
import com.nammamedmate.teleconsult.application.port.out.CartPort;
import com.nammamedmate.teleconsult.application.port.out.EPrescriptionWritePort;
import com.nammamedmate.teleconsult.application.port.out.EPrescriptionWritePort.CreateRequest;
import com.nammamedmate.teleconsult.application.port.out.EPrescriptionWritePort.Issued;
import com.nammamedmate.teleconsult.application.port.out.EPrescriptionWritePort.MedicineLine;
import com.nammamedmate.teleconsult.application.port.out.NotificationDispatchPort;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TeleconsultBridgeConfigTest {

  private final TeleconsultBridgeConfig config = new TeleconsultBridgeConfig();

  @Test
  void cartPortValidatesActiveOwnership() {
    org.springframework.jdbc.core.JdbcTemplate jdbc =
        mock(org.springframework.jdbc.core.JdbcTemplate.class);
    CartPort port = config.jdbcTeleconsultCartPort(jdbc);
    UUID cart = UUID.randomUUID();
    UUID customer = UUID.randomUUID();

    assertThat(port.isActiveCartOwnedBy(null, customer)).isFalse();
    assertThat(port.isActiveCartOwnedBy(cart, null)).isFalse();

    when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(cart), eq(customer)))
        .thenReturn(true);
    assertThat(port.isActiveCartOwnedBy(cart, customer)).isTrue();
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(cart), eq(customer)))
        .thenReturn(false);
    assertThat(port.isActiveCartOwnedBy(cart, customer)).isFalse();
  }

  @Test
  void cartLinkDelegatesToOrderLink() {
    OrderLinkPort orderLink = mock(OrderLinkPort.class);
    CartLinkPort link = config.jdbcTeleconsultCartLinkPort(orderLink);
    UUID customer = UUID.randomUUID();
    UUID cart = UUID.randomUUID();
    UUID rx = UUID.randomUUID();
    link.attachPrescription(customer, cart, rx);
    verify(orderLink).attachToCart(customer, cart, rx);
  }

  @Test
  void ePrescriptionWriteDelegatesToService() {
    EPrescriptionService service = mock(EPrescriptionService.class);
    Instant now = Instant.parse("2026-07-24T10:40:00Z");
    UUID id = UUID.randomUUID();
    when(service.createFromTeleconsult(any()))
        .thenReturn(
            new Created(
                id,
                "RX-20260724-NMM-000001",
                "hash",
                now.plusSeconds(100),
                now,
                List.of(new MedicinePrescribed("M", "1", "od", 1, "ml", null, null))));
    EPrescriptionWritePort port = config.jdbcEPrescriptionWritePort(service);
    Issued issued =
        port.create(
            new CreateRequest(
                id,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Dr",
                "MBBS",
                "R1",
                "GP",
                "Pat",
                List.of(new MedicineLine("M", "1", "od", 1, "ml", null, null)),
                false,
                null,
                null,
                now));
    assertThat(issued.prescriptionId()).isEqualTo(id);
    assertThat(issued.rxId()).isEqualTo("RX-20260724-NMM-000001");
    assertThat(issued.medicines()).hasSize(1);
  }

  @Test
  void notificationPortPublishesOutbox() {
    OutboxPublisher outbox = mock(OutboxPublisher.class);
    NotificationDispatchPort port = config.outboxTeleconsultNotificationPort(outbox);
    UUID customer = UUID.randomUUID();
    UUID consult = UUID.randomUUID();
    port.notifyConsultAutoCancelled(customer, consult);
    ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
    verify(outbox).publish(captor.capture());
    assertThat(captor.getValue().type()).isEqualTo("teleconsult.notification.auto_cancelled");
    assertThat(captor.getValue().aggregateId()).isEqualTo(consult);

    port.notifyConsultStatusUpdated(customer, consult, "IN_CALL");
    verify(outbox, org.mockito.Mockito.times(2)).publish(captor.capture());
    assertThat(captor.getValue().type()).isEqualTo("teleconsult.notification.status_updated");
  }
}
