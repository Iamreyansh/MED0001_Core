package com.nammamedmate.support;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.support.adapter.out.messaging.StubAutomationEscalate;
import com.nammamedmate.support.adapter.out.messaging.StubSupportNotificationDispatch;
import com.nammamedmate.support.application.port.out.AutomationEscalatePort;
import com.nammamedmate.support.application.port.out.CustomerLookupPort;
import com.nammamedmate.support.application.port.out.NotificationDispatchPort;
import com.nammamedmate.support.application.port.out.OrderContextPort;
import com.nammamedmate.support.application.port.out.RefundPort;
import com.nammamedmate.support.application.port.out.SupportAuditPort;
import com.nammamedmate.support.application.port.out.TicketStore;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class SupportConfig {

  @Bean
  @ConditionalOnMissingBean(Clock.class)
  Clock supportClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean(CustomerLookupPort.class)
  CustomerLookupPort stubCustomerLookupPort() {
    return new CustomerLookupPort() {
      @Override
      public Optional<CustomerContext> find(UUID customerId) {
        if (customerId == null) {
          return Optional.empty();
        }
        return Optional.of(new CustomerContext(customerId, "Customer", 0, 0));
      }

      @Override
      public Optional<String> displayName(UUID customerId) {
        return customerId == null ? Optional.empty() : Optional.of("Customer");
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean(NotificationDispatchPort.class)
  NotificationDispatchPort supportNotificationDispatchPort(
      org.springframework.beans.factory.ObjectProvider<OutboxPublisher> outbox) {
    OutboxPublisher publisher = outbox.getIfAvailable();
    if (publisher != null) {
      return new StubSupportNotificationDispatch(publisher);
    }
    return new NotificationDispatchPort() {
      @Override
      public void notifyEscalation(UUID ticketId, UUID customerId, String slaLevel) {}

      @Override
      public void notifyCsatSurvey(UUID ticketId, UUID customerId, String channel) {}

      @Override
      public void notifySupervisorEscalation(UUID ticketId, String reason) {}
    };
  }

  @Bean
  @ConditionalOnMissingBean(AutomationEscalatePort.class)
  AutomationEscalatePort stubAutomationEscalatePort(
      TicketStore tickets,
      NotificationDispatchPort notifications,
      org.springframework.beans.factory.ObjectProvider<OutboxPublisher> outbox,
      Clock clock) {
    return new StubAutomationEscalate(tickets, notifications, outbox.getIfAvailable(), clock);
  }

  @Bean
  @ConditionalOnMissingBean(SupportAuditPort.class)
  SupportAuditPort stubSupportAuditPort(
      org.springframework.beans.factory.ObjectProvider<OutboxPublisher> outbox) {
    OutboxPublisher publisher = outbox.getIfAvailable();
    return (entityType, actorId, actorRole, entityId, action, before, after) -> {
      if (publisher == null) {
        return;
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("entity_type", entityType);
      payload.put("actor_id", actorId == null ? null : actorId.toString());
      payload.put("actor_role", actorRole);
      payload.put("entity_id", entityId == null ? null : entityId.toString());
      payload.put("action", action);
      payload.put("before", before);
      payload.put("after", after);
      publisher.publish(
          DomainEvent.of(
              "support.audit.append",
              entityType,
              entityId == null ? Ids.newId() : entityId,
              payload));
    };
  }

  @Bean
  @ConditionalOnMissingBean(OrderContextPort.class)
  OrderContextPort stubOrderContextPort() {
    return orderId -> {
      if (orderId == null) {
        return Optional.empty();
      }
      return Optional.of(
          new OrderContextPort.OrderContext(
              orderId,
              UUID.fromString("c0000001-0000-4000-8000-000000000001"),
              "DELIVERED",
              9600L,
              List.of(new OrderContextPort.OrderItem("Paracetamol 500mg", 2, 4800L)),
              "Stub Pharmacy",
              "Stub Rider",
              "https://tracking.nammamedmate.com/" + orderId));
    };
  }

  @Bean
  @ConditionalOnMissingBean(RefundPort.class)
  RefundPort stubRefundPort() {
    return (orderId, customerId, amountPaise, refundTo, disputeId) ->
        new RefundPort.RefundResult("txn_" + Ids.newId(), true);
  }
}
