package com.nammamedmate.automation.adapter.out.persistence;

import com.nammamedmate.automation.application.port.out.SimulationNotifyPort;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Stub/outbox notify when SIMULATING auto-reverts (AC-008). Full email/push lands with STORY-005.
 */
@Component
public class StubSimulationNotifyAdapter implements SimulationNotifyPort {

  private static final Logger log = LoggerFactory.getLogger(StubSimulationNotifyAdapter.class);

  private final ObjectProvider<OutboxPublisher> outbox;

  public StubSimulationNotifyAdapter(ObjectProvider<OutboxPublisher> outbox) {
    this.outbox = outbox;
  }

  @Override
  public void simulatingAutoReverted(UUID ruleId, UUID adminUserId, String ruleName) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("rule_id", ruleId == null ? null : ruleId.toString());
    payload.put("rule_name", ruleName);
    payload.put("admin_user_id", adminUserId == null ? null : adminUserId.toString());
    payload.put("channel", "email_push");
    payload.put("template", "AUTOMATION_SIMULATING_AUTO_REVERT");
    log.info("automation simulating auto-revert notify rule_id={} admin={}", ruleId, adminUserId);
    OutboxPublisher publisher = outbox.getIfAvailable();
    if (publisher != null && ruleId != null) {
      publisher.publish(
          DomainEvent.of(
              "automation.simulating_auto_reverted", "automation_rule", ruleId, payload));
    }
  }
}
