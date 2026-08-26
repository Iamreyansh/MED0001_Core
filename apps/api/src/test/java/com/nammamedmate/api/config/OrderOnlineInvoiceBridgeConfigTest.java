package com.nammamedmate.api.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.order.application.port.out.DeliveryInvoicePort;
import com.nammamedmate.order.domain.Order;
import com.nammamedmate.order.domain.OrderItemSnapshot;
import com.nammamedmate.order.domain.OrderStatus;
import com.nammamedmate.order.domain.PaymentMethod;
import com.nammamedmate.order.domain.PaymentStatus;
import com.nammamedmate.pos.application.port.out.InvoiceStore;
import com.nammamedmate.pos.domain.InvoiceChannel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class OrderOnlineInvoiceBridgeConfigTest {

  @Test
  @SuppressWarnings("unchecked")
  void createsOnlineInvoiceOnce() {
    InvoiceStore invoices = mock(InvoiceStore.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    Clock clock = Clock.fixed(Instant.parse("2026-08-22T12:00:00Z"), ZoneOffset.UTC);
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenReturn(List.<String[]>of(new String[] {"Priya", "9999999999"}));
    when(invoices.getOrCreateSettings(any()))
        .thenReturn(new InvoiceStore.InvoiceSettingsRow("INV"));
    when(invoices.nextSequence(any(), anyInt(), anyInt())).thenReturn(1);

    DeliveryInvoicePort port =
        new OrderOnlineInvoiceBridgeConfig().onlineDeliveryInvoicePort(invoices, jdbc, clock);
    port.onDelivered(null);
    UUID pharmacy = UUID.randomUUID();
    UUID customer = UUID.randomUUID();
    Order order =
        new Order(
            UUID.randomUUID(),
            "ORD-1",
            customer,
            pharmacy,
            UUID.randomUUID(),
            List.of(new OrderItemSnapshot(UUID.randomUUID(), "Para", 1, 1000, 1000, false)),
            1000,
            null,
            0,
            0,
            0,
            0,
            1000,
            PaymentMethod.UPI,
            PaymentStatus.PAID,
            null,
            null,
            null,
            UUID.randomUUID(),
            null,
            OrderStatus.DELIVERED,
            null,
            null,
            null,
            Instant.parse("2026-08-22T12:00:00Z"),
            Instant.parse("2026-08-22T12:00:00Z"),
            Instant.parse("2026-08-22T12:00:00Z"),
            Instant.parse("2026-08-22T12:00:00Z"));
    port.onDelivered(order);
    verify(invoices)
        .insert(
            org.mockito.ArgumentMatchers.argThat(inv -> inv.channel() == InvoiceChannel.ONLINE));
    verify(invoices).insertItems(any());

    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy), any()))
        .thenReturn(List.of(UUID.randomUUID()));
    port.onDelivered(order);
  }
}
