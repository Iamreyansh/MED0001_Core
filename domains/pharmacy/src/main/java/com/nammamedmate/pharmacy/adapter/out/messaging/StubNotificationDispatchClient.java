package com.nammamedmate.pharmacy.adapter.out.messaging;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.pharmacy.application.port.out.NotificationDispatchPort;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** ponytail: outbox-only until EPIC-002 notification worker delivers WA/in-app. */
public final class StubNotificationDispatchClient implements NotificationDispatchPort {

  private final OutboxPublisher outbox;

  public StubNotificationDispatchClient(OutboxPublisher outbox) {
    this.outbox = outbox;
  }

  @Override
  public void dispatchPerformanceAlert(
      UUID pharmacyId, String alertType, String message, List<String> channels) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("notification_id", Ids.newId().toString());
    payload.put("pharmacy_id", pharmacyId.toString());
    payload.put("alert_type", alertType);
    payload.put("channels", channels);
    payload.put("message", message);
    payload.put("template", "PHARMACY_ALERT_" + alertType);
    outbox.publish(
        DomainEvent.of("pharmacy.notification.performance_alert", "pharmacy", pharmacyId, payload));
  }

  @Override
  public void dispatchSettlementReleased(UUID pharmacyId, UUID settlementId, long netPaidPaise) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("notification_id", Ids.newId().toString());
    payload.put("pharmacy_id", pharmacyId.toString());
    payload.put("settlement_id", settlementId.toString());
    payload.put("net_paid_paise", netPaidPaise);
    payload.put("template", "PHARMACY_SETTLEMENT_RELEASED");
    outbox.publish(
        DomainEvent.of(
            "pharmacy.notification.settlement_released", "pharmacy", pharmacyId, payload));
  }

  @Override
  public void dispatchSettlementPaid(
      UUID pharmacyId, UUID settlementId, long netPaidPaise, String utrNumber) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("notification_id", Ids.newId().toString());
    payload.put("pharmacy_id", pharmacyId.toString());
    payload.put("settlement_id", settlementId.toString());
    payload.put("net_paid_paise", netPaidPaise);
    payload.put("utr_number", utrNumber);
    payload.put("template", "PHARMACY_SETTLEMENT_PAID");
    outbox.publish(
        DomainEvent.of("pharmacy.notification.settlement_paid", "pharmacy", pharmacyId, payload));
  }

  @Override
  public void dispatchSettlementHeld(UUID pharmacyId, UUID settlementId, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("notification_id", Ids.newId().toString());
    payload.put("pharmacy_id", pharmacyId.toString());
    payload.put("settlement_id", settlementId.toString());
    payload.put("reason", reason);
    payload.put("template", "PHARMACY_SETTLEMENT_HELD");
    outbox.publish(
        DomainEvent.of("pharmacy.notification.settlement_held", "pharmacy", pharmacyId, payload));
  }

  @Override
  public void dispatchPharmacyNotice(
      UUID pharmacyId,
      List<String> channels,
      String templateName,
      String subject,
      String message,
      String priority) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("notification_id", Ids.newId().toString());
    payload.put("pharmacy_id", pharmacyId.toString());
    payload.put("channels", channels);
    payload.put("template", templateName);
    payload.put("subject", subject);
    payload.put("message", message);
    payload.put("priority", priority);
    outbox.publish(DomainEvent.of("pharmacy.notification.notice", "pharmacy", pharmacyId, payload));
  }
}
