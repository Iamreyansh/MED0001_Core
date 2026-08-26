package com.nammamedmate.automation.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nammamedmate.automation.application.RulesEngineService;
import com.nammamedmate.automation.application.RulesEngineService.EventPayload;
import com.nammamedmate.automation.application.WorkflowEngineService;
import com.nammamedmate.automation.domain.TriggerIdMapper;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxMessage;
import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AutomationTriggerConsumerTest {

  private final RulesEngineService engine = mock(RulesEngineService.class);
  private final WorkflowEngineService workflows = mock(WorkflowEngineService.class);
  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
  private final AutomationTriggerConsumer consumer =
      new AutomationTriggerConsumer(engine, workflows, mapper);

  @Test
  void mapsOrderPlacedAndEvaluates() throws Exception {
    UUID orderId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    DomainEvent event =
        DomainEvent.of(
            "order.placed",
            "order",
            orderId,
            Map.of("order_number", "NMM-1", "customer_id", "22222222-2222-4222-8222-222222222222"));
    consumer.accept(
        new OutboxMessage(
            event.eventId(), event.type(), mapper.writeValueAsString(event), Instant.now(), false));
    verify(engine).evaluateMatching(any(EventPayload.class));
    verify(workflows).onTrigger(eq("order_placed"), eq("ORDER"), eq(orderId), eq("NMM-1"), any());
  }

  @Test
  void ignoresUnmappedAndNull() {
    consumer.accept(null);
    consumer.accept(new OutboxMessage(UUID.randomUUID(), null, "{}", Instant.now(), false));
    consumer.accept(new OutboxMessage(UUID.randomUUID(), "foo.bar", "{}", Instant.now(), false));
    verify(engine, never()).evaluateMatching(any());
  }

  @Test
  void unreadableJsonStillEvaluatesWithTypeMapping() {
    UUID id = UUID.fromString("33333333-3333-4333-8333-333333333333");
    consumer.accept(new OutboxMessage(id, "payment.failed", "{not-json", Instant.now(), false));
    verify(engine).evaluateMatching(any(EventPayload.class));
    verify(workflows, never()).onTrigger(any(), any(), any(), any(), any());
  }

  @Test
  void usesPayloadEntityIdAndType() throws Exception {
    UUID ticket = UUID.fromString("44444444-4444-4444-8444-444444444444");
    DomainEvent event =
        DomainEvent.of(
            "support.automation.sla_escalate",
            "support_ticket",
            UUID.randomUUID(),
            Map.of("ticket_id", ticket.toString(), "entity_type", "TICKET", "entity_name", "SLA"));
    consumer.accept(
        new OutboxMessage(
            event.eventId(), event.type(), mapper.writeValueAsString(event), Instant.now(), false));
    verify(workflows)
        .onTrigger(eq("support_sla_breaching"), eq("TICKET"), eq(ticket), eq("SLA"), any());
  }

  @Test
  void usesUuidPayloadAndPaymentId() throws Exception {
    UUID order = UUID.fromString("55555555-5555-4555-8555-555555555555");
    UUID pay = UUID.fromString("66666666-6666-4666-8666-666666666666");
    ObjectMapper stub = mock(ObjectMapper.class);
    when(stub.readValue(eq("uuid-payload"), any(Class.class)))
        .thenReturn(DomainEvent.of("order.cancelled", "order", null, Map.of("order_id", order)));
    new AutomationTriggerConsumer(engine, workflows, stub)
        .accept(
            new OutboxMessage(
                UUID.randomUUID(), "order.cancelled", "uuid-payload", Instant.now(), false));
    verify(workflows).onTrigger(eq("order_cancelled"), eq("ORDER"), eq(order), eq(null), any());

    when(stub.readValue(eq("pay-payload"), any(Class.class)))
        .thenReturn(
            DomainEvent.of(
                "payment.failed",
                null,
                pay,
                Map.of("payment_id", pay.toString(), "entity_id", "bad", "entity_type", "  ")));
    new AutomationTriggerConsumer(engine, workflows, stub)
        .accept(
            new OutboxMessage(
                UUID.randomUUID(), "payment.failed", "pay-payload", Instant.now(), false));
    verify(workflows).onTrigger(eq("payment_failed"), eq("UNKNOWN"), eq(pay), eq(null), any());

    when(stub.readValue(eq("blank-agg"), any(Class.class)))
        .thenReturn(DomainEvent.of("order.unassigned", "  ", order, Map.of()));
    new AutomationTriggerConsumer(engine, workflows, stub)
        .accept(
            new OutboxMessage(
                UUID.randomUUID(), "order.unassigned", "blank-agg", Instant.now(), false));
    verify(workflows).onTrigger(eq("order_unassigned"), eq("UNKNOWN"), eq(order), eq(null), any());
  }

  @Test
  void mapperCoversKnownAliasesAndSlugs() throws Exception {
    assertThat(TriggerIdMapper.fromEventType(null)).isEmpty();
    assertThat(TriggerIdMapper.fromEventType(" ")).isEmpty();
    assertThat(TriggerIdMapper.fromEventType("order.placed.pharmacy_notified"))
        .contains("order_placed");
    assertThat(TriggerIdMapper.fromEventType("order.delivered")).contains("order_delivered");
    assertThat(TriggerIdMapper.fromEventType("order.cancelled")).contains("order_cancelled");
    assertThat(TriggerIdMapper.fromEventType("order.assignment.timed_out"))
        .contains("order_unassigned");
    assertThat(TriggerIdMapper.fromEventType("order.rider.escalation"))
        .contains("order_unassigned");
    assertThat(TriggerIdMapper.fromEventType("order.unassigned")).contains("order_unassigned");
    assertThat(TriggerIdMapper.fromEventType("ticket.sla_breaching"))
        .contains("support_sla_breaching");
    assertThat(TriggerIdMapper.fromEventType("sla_breaching")).contains("support_sla_breaching");
    assertThat(TriggerIdMapper.fromEventType("support.sla_breaching"))
        .contains("support_sla_breaching");
    assertThat(TriggerIdMapper.fromEventType("order_placed")).contains("order_placed");
    assertThat(TriggerIdMapper.fromEventType("unknown.dotted")).isEmpty();
    Constructor<TriggerIdMapper> ctor = TriggerIdMapper.class.getDeclaredConstructor();
    ctor.setAccessible(true);
    ctor.newInstance();
  }
}
