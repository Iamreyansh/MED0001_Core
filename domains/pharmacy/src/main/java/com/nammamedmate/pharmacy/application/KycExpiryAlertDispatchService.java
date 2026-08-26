package com.nammamedmate.pharmacy.application;

import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Dispatches due KYC expiry alerts and stamps sent_at (V132). */
@Service
public class KycExpiryAlertDispatchService {

  private final JdbcTemplate jdbc;
  private final OutboxPublisher outbox;
  private final Clock clock;

  public KycExpiryAlertDispatchService(JdbcTemplate jdbc, OutboxPublisher outbox, Clock clock) {
    this.jdbc = jdbc;
    this.outbox = outbox;
    this.clock = clock;
  }

  @Transactional
  public int dispatchDue() {
    Instant now = clock.instant();
    List<DueAlert> due =
        jdbc.query(
            """
            SELECT id, document_id, pharmacy_id, template
              FROM kyc_expiry_alerts
             WHERE alert_at <= ?
               AND sent_at IS NULL
             ORDER BY alert_at ASC
             LIMIT 100
            """,
            (rs, i) ->
                new DueAlert(
                    (UUID) rs.getObject("id"),
                    (UUID) rs.getObject("document_id"),
                    (UUID) rs.getObject("pharmacy_id"),
                    rs.getString("template")),
            Timestamp.from(now));
    int n = 0;
    for (DueAlert alert : due) {
      outbox.publish(
          DomainEvent.of(
              "pharmacy.kyc.expiry_alert",
              "pharmacy",
              alert.pharmacyId(),
              Map.of(
                  "alert_id",
                  alert.id().toString(),
                  "document_id",
                  alert.documentId().toString(),
                  "pharmacy_id",
                  alert.pharmacyId().toString(),
                  "template",
                  alert.template() == null ? "" : alert.template())));
      jdbc.update(
          "UPDATE kyc_expiry_alerts SET sent_at = ? WHERE id = ? AND sent_at IS NULL",
          Timestamp.from(now),
          alert.id());
      n++;
    }
    return n;
  }

  private record DueAlert(UUID id, UUID documentId, UUID pharmacyId, String template) {}
}
