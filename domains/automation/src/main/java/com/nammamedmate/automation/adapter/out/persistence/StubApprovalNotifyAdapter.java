package com.nammamedmate.automation.adapter.out.persistence;

import com.nammamedmate.automation.application.port.out.ApprovalNotifyPort;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** HIGH-priority approver notify via transactional outbox (EPIC-017 transport). */
@Component
public class StubApprovalNotifyAdapter implements ApprovalNotifyPort {

  private static final Logger log = LoggerFactory.getLogger(StubApprovalNotifyAdapter.class);

  private final ObjectProvider<OutboxPublisher> outbox;

  public StubApprovalNotifyAdapter(ObjectProvider<OutboxPublisher> outbox) {
    this.outbox = outbox;
  }

  @Override
  public void approvalRequested(
      UUID approvalId, String actionType, String urgency, String deepLink) {
    Map<String, Object> payload = base(approvalId, actionType);
    payload.put("urgency", urgency);
    payload.put("priority", "HIGH");
    payload.put("deep_link", deepLink);
    payload.put("roles", List.of("admin_super", "admin_operations"));
    publish("automation.approval.requested", approvalId, payload);
  }

  @Override
  public void approvalExpired(UUID approvalId, String actionType) {
    Map<String, Object> payload = base(approvalId, actionType);
    payload.put("priority", "HIGH");
    publish("automation.approval.expired", approvalId, payload);
  }

  private void publish(String type, UUID approvalId, Map<String, Object> payload) {
    log.info("{} approval_id={} action={}", type, approvalId, payload.get("action_type"));
    OutboxPublisher publisher = outbox.getIfAvailable();
    if (publisher != null && approvalId != null) {
      publisher.publish(DomainEvent.of(type, "automation_approval", approvalId, payload));
    }
  }

  private static Map<String, Object> base(UUID approvalId, String actionType) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("approval_id", approvalId == null ? null : approvalId.toString());
    payload.put("action_type", actionType);
    payload.put("channel", "push");
    payload.put("template", "AUTOMATION_APPROVAL");
    return payload;
  }
}
