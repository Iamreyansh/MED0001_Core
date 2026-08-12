package com.nammamedmate.observability_ops.adapter.out.messaging;

import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.observability_ops.application.port.out.NotificationDispatchPort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Alert/incident paging via transactional outbox (ids-only). */
@Component
public class OutboxNotificationDispatchAdapter implements NotificationDispatchPort {

  private static final Logger log =
      LoggerFactory.getLogger(OutboxNotificationDispatchAdapter.class);

  private final ObjectProvider<OutboxPublisher> outbox;
  private final List<DispatchRecord> dispatched = new ArrayList<>();

  public OutboxNotificationDispatchAdapter(ObjectProvider<OutboxPublisher> outbox) {
    this.outbox = outbox;
  }

  @Override
  public void pageCritical(UUID alertId, String alertType, List<UUID> adminIds) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("alert_id", alertId == null ? null : alertId.toString());
    payload.put("alert_type", alertType);
    payload.put(
        "admin_ids", adminIds == null ? List.of() : adminIds.stream().map(UUID::toString).toList());
    payload.put("channels", List.of("push", "sms"));
    payload.put("priority", "HIGH");
    payload.put("roles", List.of("admin_super", "admin_operations"));
    record(
        "alert",
        alertId,
        alertType,
        List.copyOf(adminIds == null ? List.of() : adminIds),
        List.of("push", "sms"),
        "HIGH");
    log.info("observability.alert.critical_page alert_id={} type={}", alertId, alertType);
    OutboxPublisher publisher = outbox.getIfAvailable();
    if (publisher != null && alertId != null) {
      publisher.publish(
          DomainEvent.of(
              "observability.alert.critical_page", "monitoring_alert", alertId, payload));
    }
  }

  @Override
  public void pageIncident(UUID incidentId, String severity, List<UUID> adminIds) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("incident_id", incidentId == null ? null : incidentId.toString());
    payload.put("severity", severity);
    payload.put(
        "admin_ids", adminIds == null ? List.of() : adminIds.stream().map(UUID::toString).toList());
    payload.put("channels", List.of("push"));
    payload.put("priority", "HIGH");
    payload.put("roles", List.of("admin_super", "admin_operations"));
    record(
        "incident",
        incidentId,
        severity,
        List.copyOf(adminIds == null ? List.of() : adminIds),
        List.of("push"),
        "HIGH");
    log.info("observability.incident.declared incident_id={} severity={}", incidentId, severity);
    OutboxPublisher publisher = outbox.getIfAvailable();
    if (publisher != null && incidentId != null) {
      publisher.publish(
          DomainEvent.of(
              "observability.incident.declared", "monitoring_incident", incidentId, payload));
    }
  }

  @Override
  public void remindPostmortem(UUID incidentId, List<UUID> adminIds) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("incident_id", incidentId == null ? null : incidentId.toString());
    payload.put(
        "admin_ids", adminIds == null ? List.of() : adminIds.stream().map(UUID::toString).toList());
    payload.put("channels", List.of("push"));
    payload.put("priority", "HIGH");
    payload.put("roles", List.of("admin_super"));
    record(
        "postmortem_reminder",
        incidentId,
        null,
        List.copyOf(adminIds == null ? List.of() : adminIds),
        List.of("push"),
        "HIGH");
    log.info("observability.incident.postmortem_reminder incident_id={}", incidentId);
    OutboxPublisher publisher = outbox.getIfAvailable();
    if (publisher != null && incidentId != null) {
      publisher.publish(
          DomainEvent.of(
              "observability.incident.postmortem_reminder",
              "monitoring_incident",
              incidentId,
              payload));
    }
  }

  private void record(
      String kind,
      UUID entityId,
      String typeOrSeverity,
      List<UUID> adminIds,
      List<String> channels,
      String priority) {
    synchronized (dispatched) {
      dispatched.add(
          new DispatchRecord(kind, entityId, typeOrSeverity, adminIds, channels, priority));
    }
  }

  public List<DispatchRecord> dispatched() {
    synchronized (dispatched) {
      return List.copyOf(dispatched);
    }
  }

  public void clearDispatched() {
    synchronized (dispatched) {
      dispatched.clear();
    }
  }

  public record DispatchRecord(
      String kind,
      UUID entityId,
      String typeOrSeverity,
      List<UUID> adminIds,
      List<String> channels,
      String priority) {
    public DispatchRecord {
      adminIds = List.copyOf(adminIds);
      channels = List.copyOf(channels);
    }

    /** Back-compat for STORY-001/002 tests. */
    public UUID alertId() {
      return "alert".equals(kind) ? entityId : null;
    }

    public String alertType() {
      return "alert".equals(kind) ? typeOrSeverity : null;
    }
  }
}
