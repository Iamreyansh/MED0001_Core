package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.medicine_schedule.application.port.out.CustomerNamePort;
import com.nammamedmate.medicine_schedule.application.port.out.NotificationDispatchPort;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class MedicineScheduleBridgeConfigTest {

  @Test
  @SuppressWarnings("unchecked")
  void jdbcCustomerNamePort() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    UUID id = UUID.randomUUID();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id))).thenReturn(List.of("Priya"));
    CustomerNamePort port = new MedicineScheduleBridgeConfig().jdbcCustomerNamePort(jdbc);
    assertThat(port.nameFor(id)).isEqualTo("Priya");
    assertThat(port.nameFor(null)).isEqualTo("Customer");
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
    assertThat(port.nameFor(UUID.randomUUID())).isEqualTo("Customer");
  }

  @Test
  void outboxNotificationPort() {
    OutboxPublisher outbox = mock(OutboxPublisher.class);
    NotificationDispatchPort port =
        new MedicineScheduleBridgeConfig().outboxMedicineScheduleNotificationPort(outbox);
    UUID customerId = Ids.newId();
    UUID reminderId = Ids.newId();
    port.notifyDoseReminderDue(customerId, reminderId, Ids.newId(), Ids.newId());
    ArgumentCaptor<DomainEvent> doseCaptor = ArgumentCaptor.forClass(DomainEvent.class);
    verify(outbox).publish(doseCaptor.capture());
    assertThat(doseCaptor.getValue().type())
        .isEqualTo("medicine_schedule.notification.dose_reminder");

    UUID medicineId = Ids.newId();
    port.notifyRefillAlert(customerId, medicineId, 8, 10);
    ArgumentCaptor<DomainEvent> refillCaptor = ArgumentCaptor.forClass(DomainEvent.class);
    verify(outbox, org.mockito.Mockito.times(2)).publish(refillCaptor.capture());
    assertThat(refillCaptor.getAllValues().getLast().type())
        .isEqualTo("medicine_schedule.notification.refill_alert");
  }
}
