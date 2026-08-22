package com.nammamedmate.automation.adapter.out.executor;

import com.nammamedmate.automation.application.port.out.ActionExecutorPort;
import com.nammamedmate.automation.application.port.out.ActivityLogPort;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Persists activity and publishes automation.action.executed for worker/side-effect consumers. */
public class OutboxActionExecutor implements ActionExecutorPort {

  private final StubActionExecutor delegate;
  private final OutboxPublisher outbox;

  public OutboxActionExecutor(ActivityLogPort activityLog, OutboxPublisher outbox) {
    this.delegate = new StubActionExecutor(activityLog);
    this.outbox = outbox;
  }

  @Override
  public UUID execute(String actionId, Map<String, Object> params, Map<String, Object> context) {
    UUID activityId = delegate.execute(actionId, params, context);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("activity_id", activityId.toString());
    payload.put("action_id", actionId == null ? "" : actionId);
    payload.put("params", params == null ? Map.of() : params);
    payload.put("context", context == null ? Map.of() : context);
    outbox.publish(DomainEvent.of("automation.action.executed", "automation", activityId, payload));
    return activityId;
  }
}
