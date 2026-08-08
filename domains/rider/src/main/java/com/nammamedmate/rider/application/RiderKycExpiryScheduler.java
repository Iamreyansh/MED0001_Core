package com.nammamedmate.rider.application;

import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.rider.application.port.out.RiderKycDocumentStore;
import com.nammamedmate.rider.application.port.out.RiderKycDocumentStore.DocumentRecord;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** AC-010: alert 30 days before insurance/PUC expiry; set expiry_alert_sent to prevent dupes. */
@Component
@ConditionalOnProperty(
    name = "medmate.rider.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class RiderKycExpiryScheduler {

  private final RiderKycDocumentStore docs;
  private final OutboxPublisher outbox;
  private final Clock clock;

  public RiderKycExpiryScheduler(RiderKycDocumentStore docs, OutboxPublisher outbox, Clock clock) {
    this.docs = docs;
    this.outbox = outbox;
    this.clock = clock;
  }

  @Scheduled(cron = "0 0 7 * * *", zone = "Asia/Kolkata")
  @Transactional
  public void dispatchExpiryAlerts() {
    LocalDate today = LocalDate.now(clock);
    LocalDate horizon = today.plusDays(30);
    List<DocumentRecord> due = docs.findDueForExpiryAlert(horizon, today);
    for (DocumentRecord doc : due) {
      outbox.publish(
          DomainEvent.of(
              "rider.notification.document_expiry",
              "rider",
              doc.riderId(),
              Map.of(
                  "rider_id",
                  doc.riderId().toString(),
                  "document_id",
                  doc.id().toString(),
                  "document_type",
                  doc.documentType(),
                  "template",
                  "RIDER_DOCUMENT_EXPIRY_30",
                  "channels",
                  List.of("PUSH"))));
      docs.markExpiryAlertSent(doc.id());
    }
  }
}
