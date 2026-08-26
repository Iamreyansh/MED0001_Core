package com.nammamedmate.rider.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxMessage;
import com.nammamedmate.rider.application.DispatchService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AutomationRiderAssignConsumerTest {

  @Test
  void assignsWhenOrderPresent() {
    DispatchService dispatch = mock(DispatchService.class);
    ObjectMapper mapper = new ObjectMapper();
    AutomationRiderAssignConsumer consumer = new AutomationRiderAssignConsumer(dispatch, mapper);
    UUID orderId = UUID.fromString("aaaaaaaa-0001-4000-8000-000000000001");
    String json =
        "{\"type\":\"automation.rider.assign_requested\",\"payload\":{\"order_id\":\""
            + orderId
            + "\"}}";
    consumer.accept(
        new OutboxMessage(
            UUID.randomUUID(), "automation.rider.assign_requested", json, Instant.now(), false));
    verify(dispatch).autoAssignOrder(orderId);
  }

  @Test
  void ignoresOtherTypes() {
    DispatchService dispatch = mock(DispatchService.class);
    AutomationRiderAssignConsumer consumer =
        new AutomationRiderAssignConsumer(dispatch, new ObjectMapper());
    consumer.accept(
        new OutboxMessage(UUID.randomUUID(), "order.placed", "{}", Instant.now(), false));
    consumer.accept(null);
    verifyNoInteractions(dispatch);
  }

  @Test
  void parseFailuresAndAggregateFallback() throws Exception {
    DispatchService dispatch = mock(DispatchService.class);
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    AutomationRiderAssignConsumer consumer = new AutomationRiderAssignConsumer(dispatch, mapper);

    consumer.accept(
        new OutboxMessage(
            UUID.randomUUID(),
            "automation.rider.assign_requested",
            "{not-json",
            Instant.now(),
            false));
    verifyNoInteractions(dispatch);

    UUID fromAggregate = UUID.fromString("bbbbbbbb-0001-4000-8000-000000000001");
    DomainEvent viaAggregate =
        new DomainEvent(
            UUID.randomUUID(),
            "automation.rider.assign_requested",
            "order",
            fromAggregate,
            Instant.now(),
            Map.of("order_id", "not-a-uuid"));
    consumer.accept(
        new OutboxMessage(
            UUID.randomUUID(),
            "automation.rider.assign_requested",
            mapper.writeValueAsString(viaAggregate),
            Instant.now(),
            false));
    verify(dispatch).autoAssignOrder(fromAggregate);

    DomainEvent missingIds =
        new DomainEvent(
            UUID.randomUUID(),
            "automation.rider.assign_requested",
            "order",
            null,
            Instant.now(),
            Map.of());
    consumer.accept(
        new OutboxMessage(
            UUID.randomUUID(),
            "automation.rider.assign_requested",
            mapper.writeValueAsString(missingIds),
            Instant.now(),
            false));

    var asUuid = AutomationRiderAssignConsumer.class.getDeclaredMethod("asUuid", Object.class);
    asUuid.setAccessible(true);
    UUID raw = UUID.fromString("cccccccc-0001-4000-8000-000000000001");
    assertThat(asUuid.invoke(null, raw)).isEqualTo(raw);
    assertThat(asUuid.invoke(null, new Object[] {null})).isNull();
  }
}
