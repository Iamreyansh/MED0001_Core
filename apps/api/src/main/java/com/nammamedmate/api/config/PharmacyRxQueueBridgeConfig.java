package com.nammamedmate.api.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.crm.application.port.out.CrmPlanLookupPort;
import com.nammamedmate.crm.domain.PlanNames;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.prescription.application.port.out.NotificationDispatchPort;
import com.nammamedmate.prescription.application.port.out.OrderLinesPort;
import com.nammamedmate.prescription.application.port.out.OrderStatusPort;
import com.nammamedmate.prescription.application.port.out.PharmacyPlanPort;
import com.nammamedmate.prescription.application.port.out.PosDispensePort;
import com.nammamedmate.prescription.domain.PharmacyRxQueueEntry.ApprovedMedicine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Composition-root bridges for pharmacy Rx queue: CRM plan gate, order line/status JDBC, outbox
 * notifications, stub POS cart/sale until EPIC-006 dispense API is shared cleanly.
 */
@Configuration
public class PharmacyRxQueueBridgeConfig {

  @Bean
  @Primary
  PharmacyPlanPort crmPharmacyPlanPort(CrmPlanLookupPort lookup) {
    return pharmacyId ->
        PlanNames.starterFeaturesEnabled(
            lookup.planNameForPharmacy(pharmacyId).orElse(PlanNames.FREE));
  }

  @Bean
  @Primary
  OrderLinesPort jdbcOrderLinesPort(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
    return (orderId, medicines) -> {
      if (orderId == null) {
        return;
      }
      List<Map<String, Object>> items = new ArrayList<>();
      long subtotal = 0L;
      for (ApprovedMedicine m : medicines) {
        long unitPaise = rupeesToPaise(m.price());
        long line = unitPaise * Math.max(0, m.quantity());
        subtotal += line;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("product_id", Ids.newId());
        row.put("name", m.name());
        row.put("quantity", m.quantity());
        row.put("unit_price_paise", unitPaise);
        row.put("line_total_paise", line);
        row.put("rx_required", true);
        items.add(row);
      }
      String json;
      try {
        json = objectMapper.writeValueAsString(items);
      } catch (JsonProcessingException e) {
        json = "[]";
      }
      jdbc.update(
          """
          UPDATE orders
          SET items = ?::jsonb,
              item_total_paise = ?,
              total_payable_paise = GREATEST(
                0,
                ? + delivery_fee_paise + handling_fee_paise
                  - coupon_discount_paise - wallet_applied_paise
              ),
              updated_at = ?
          WHERE id = ? AND deleted_at IS NULL
          """,
          json,
          subtotal,
          subtotal,
          Timestamp.from(clock.instant()),
          orderId);
    };
  }

  @Bean
  @Primary
  OrderStatusPort jdbcOrderStatusPort(JdbcTemplate jdbc, Clock clock) {
    return orderId -> {
      if (orderId == null) {
        return;
      }
      Timestamp now = Timestamp.from(clock.instant());
      jdbc.update(
          """
          UPDATE orders
          SET status = 'READY_FOR_PICKUP',
              ready_for_pickup_at = COALESCE(ready_for_pickup_at, ?),
              updated_at = ?
          WHERE id = ? AND deleted_at IS NULL
          """,
          now,
          now,
          orderId);
    };
  }

  @Bean
  @Primary
  PosDispensePort stubPosDispenseBridge() {
    // ponytail: no clean cross-domain POS cart API without domain→domain dep; return UUIDs.
    return new PosDispensePort() {
      @Override
      public boolean available() {
        return true;
      }

      @Override
      public UUID pushToBillingCart(
          UUID pharmacyId, UUID staffId, List<ApprovedMedicine> medicines) {
        return Ids.newId();
      }

      @Override
      public UUID createSaleRecord(
          UUID pharmacyId, UUID staffId, UUID orderId, List<ApprovedMedicine> medicines) {
        return Ids.newId();
      }
    };
  }

  @Bean
  @Primary
  NotificationDispatchPort outboxRxNotificationPort(OutboxPublisher outbox) {
    return new NotificationDispatchPort() {
      @Override
      public void notifyCustomerRxRejected(
          UUID customerId, UUID rxId, String reason, String customMessage) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notification_id", Ids.newId().toString());
        payload.put("customer_id", customerId.toString());
        payload.put("rx_id", rxId.toString());
        payload.put("reason", reason);
        payload.put("channels", List.of("WHATSAPP", "PUSH"));
        payload.put("template", "RX_REJECTED");
        outbox.publish(
            DomainEvent.of("prescription.notification.rejected", "prescription", rxId, payload));
      }

      @Override
      public void notifyPharmacyOwnerOverdue(UUID pharmacyId, UUID rxId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notification_id", Ids.newId().toString());
        payload.put("pharmacy_id", pharmacyId.toString());
        payload.put("rx_id", rxId.toString());
        payload.put("channels", List.of("WHATSAPP"));
        payload.put("template", "RX_QUEUE_OVERDUE");
        outbox.publish(
            DomainEvent.of("prescription.notification.overdue", "prescription", rxId, payload));
      }

      @Override
      public void notifyComplianceOverdueAudit(UUID rxId, UUID pharmacyId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notification_id", Ids.newId().toString());
        payload.put("rx_id", rxId.toString());
        payload.put("pharmacy_id", pharmacyId.toString());
        payload.put("channels", List.of("EMAIL", "PUSH"));
        payload.put("template", "RX_AUDIT_OVERDUE");
        outbox.publish(
            DomainEvent.of(
                "prescription.notification.audit_overdue", "prescription", rxId, payload));
      }

      @Override
      public void notifyHeadOfComplianceFlag(UUID rxId, String severity, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notification_id", Ids.newId().toString());
        payload.put("rx_id", rxId.toString());
        payload.put("severity", severity);
        payload.put("reason_code", "RX_FLAGGED");
        payload.put("channels", List.of("EMAIL"));
        payload.put("template", "RX_FLAG_ESCALATION");
        payload.put("recipient", "head_of_compliance");
        outbox.publish(
            DomainEvent.of(
                "prescription.notification.flag_escalation", "prescription", rxId, payload));
      }

      @Override
      public void notifyComplianceDoctorScheduleAlert(UUID doctorId, long count30d) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notification_id", Ids.newId().toString());
        payload.put("doctor_id", doctorId.toString());
        payload.put("scheduled_drug_count_30d", count30d);
        payload.put("channels", List.of("EMAIL", "PUSH"));
        payload.put("template", "DOCTOR_SCHEDULE_SOFT_ALERT");
        outbox.publish(
            DomainEvent.of(
                "prescription.notification.doctor_schedule_alert", "doctor", doctorId, payload));
      }

      @Override
      public void notifyComplianceDoctorBlacklisted(UUID doctorId, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notification_id", Ids.newId().toString());
        payload.put("doctor_id", doctorId.toString());
        payload.put("reason_code", "DOCTOR_BLACKLISTED");
        payload.put("channels", List.of("EMAIL"));
        payload.put("template", "DOCTOR_BLACKLISTED");
        outbox.publish(
            DomainEvent.of(
                "prescription.notification.doctor_blacklisted", "doctor", doctorId, payload));
      }

      @Override
      public void notifyComplianceFilingOverdue(
          UUID filingId, String filingType, boolean escalation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notification_id", Ids.newId().toString());
        payload.put("filing_id", filingId.toString());
        payload.put("filing_type", filingType);
        payload.put("escalation", escalation);
        payload.put("channels", List.of("EMAIL"));
        payload.put("template", escalation ? "FILING_OVERDUE_ESCALATION" : "FILING_OVERDUE");
        payload.put("recipients", List.of("admin_compliance", "admin_super"));
        outbox.publish(
            DomainEvent.of(
                "prescription.notification.filing_overdue", "filing", filingId, payload));
      }

      @Override
      public void notifyPharmacyDrugRecall(UUID pharmacyId, String drugName, String batchNo) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notification_id", Ids.newId().toString());
        payload.put("pharmacy_id", pharmacyId.toString());
        payload.put("drug_name", drugName);
        payload.put("batch_no", batchNo);
        payload.put("channels", List.of("WHATSAPP"));
        payload.put("template", "DRUG_RECALL");
        outbox.publish(
            DomainEvent.of(
                "prescription.notification.drug_recall", "pharmacy", pharmacyId, payload));
      }
    };
  }

  private static long rupeesToPaise(BigDecimal rupees) {
    if (rupees == null) {
      return 0L;
    }
    return rupees.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValue();
  }
}
