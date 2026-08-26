package com.nammamedmate.automation.adapter.out.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.automation.application.port.out.ActivityLogPort;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutboxActionExecutorTest {

  @Test
  void appendsActivityAndPublishesSideEffects() {
    ActivityLogPort log = mock(ActivityLogPort.class);
    OutboxPublisher outbox = mock(OutboxPublisher.class);
    UUID id = UUID.randomUUID();
    UUID order = UUID.fromString("11111111-1111-4111-8111-111111111111");
    when(log.append(any(), any(), any(), any())).thenReturn(id);
    OutboxActionExecutor exec = new OutboxActionExecutor(log, outbox);
    assertThat(
            exec.execute(
                "send_notification",
                Map.of(
                    "channel",
                    "PUSH",
                    "template_id",
                    "t1",
                    "recipient_id",
                    order.toString(),
                    "payload",
                    Map.of("title", "Hi", "body", "There"),
                    "order_id",
                    order.toString()),
                Map.of("entity_type", "ORDER", "entity_id", order.toString())))
        .isEqualTo(id);
    assertThat(exec.execute("page_human", Map.of("severity", "HIGH", "message", "page"), Map.of()))
        .isEqualTo(id);
    assertThat(
            exec.execute(
                "update_order_status",
                Map.of("order_id", order.toString(), "status", "CANCELLED"),
                Map.of()))
        .isEqualTo(id);
    assertThat(
            exec.execute(
                "process_refund",
                Map.of("refund_id", id.toString(), "amount_paise", 100),
                Map.of("entity_id", order.toString())))
        .isEqualTo(id);
    assertThat(
            exec.execute(
                "auto_assign_rider",
                Map.of("order_id", order.toString(), "exclude_rider_id", "r1"),
                Map.of()))
        .isEqualTo(id);
    assertThat(exec.execute("auto_reassign_rider", Map.of(), Map.of("entity_id", order.toString())))
        .isEqualTo(id);
    assertThat(exec.execute(null, null, null)).isEqualTo(id);
    assertThat(exec.execute("flag_prescription", Map.of("x", "  "), Map.of("entity_type", "   ")))
        .isEqualTo(id);
    assertThat(exec.execute("flag_prescription", Map.of(), Map.of())).isEqualTo(id);
    assertThat(
            exec.execute(
                "send_notification", Map.of("customer_id", order, "message", "m"), Map.of()))
        .isEqualTo(id);
    assertThat(
            exec.execute(
                "send_notification",
                Map.of("title", "  ", "body", "B", "recipient_id", "bad"),
                Map.of("entity_type", "ORDER", "entity_id", order)))
        .isEqualTo(id);
    assertThat(exec.execute("page_human", Map.of(), Map.of())).isEqualTo(id);
    assertThat(exec.execute("update_order_status", Map.of(), Map.of("entity_id", order.toString())))
        .isEqualTo(id);
    assertThat(
            exec.execute(
                "update_order_status",
                Map.of("status", "CANCELLED"),
                Map.of("entity_type", "ORDER")))
        .isEqualTo(id);
    assertThat(exec.execute("process_refund", Map.of(), Map.of("amount_paise", 50L))).isEqualTo(id);
    assertThat(exec.execute("process_refund", Map.of(), Map.of())).isEqualTo(id);
    assertThat(exec.execute("auto_assign_rider", Map.of(), Map.of())).isEqualTo(id);
    verify(outbox, org.mockito.Mockito.atLeast(7)).publish(any(DomainEvent.class));
    assertThat(OutboxActionExecutor.eventType(null)).isEqualTo("automation.action.executed");
    assertThat(OutboxActionExecutor.eventType("send_notification"))
        .isEqualTo("customer.notification.requested");
  }
}
