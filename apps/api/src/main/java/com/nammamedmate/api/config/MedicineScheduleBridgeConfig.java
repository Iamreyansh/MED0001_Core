package com.nammamedmate.api.config;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.medicine_schedule.application.port.out.CustomerNamePort;
import com.nammamedmate.medicine_schedule.application.port.out.NotificationDispatchPort;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** Composition-root bridges for EPIC-018 medicine-schedule → customers JDBC + outbox push. */
@Configuration
public class MedicineScheduleBridgeConfig {

  @Bean
  @Primary
  CustomerNamePort jdbcCustomerNamePort(JdbcTemplate jdbc) {
    return customerId -> {
      if (customerId == null) {
        return "Customer";
      }
      List<String> names =
          jdbc.query(
              """
              SELECT COALESCE(NULLIF(TRIM(name), ''), 'Customer') AS name
              FROM customers
              WHERE id = ? AND deleted_at IS NULL
              """,
              (rs, i) -> rs.getString("name"),
              customerId);
      return names.isEmpty() ? "Customer" : names.getFirst();
    };
  }

  @Bean
  @Primary
  NotificationDispatchPort outboxMedicineScheduleNotificationPort(OutboxPublisher outbox) {
    return new NotificationDispatchPort() {
      @Override
      public void notifyDoseReminderDue(
          java.util.UUID customerId,
          java.util.UUID reminderId,
          java.util.UUID doseLogId,
          java.util.UUID medicineId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notification_id", Ids.newId().toString());
        payload.put("customer_id", customerId.toString());
        payload.put("reminder_id", reminderId.toString());
        payload.put("dose_log_id", doseLogId.toString());
        payload.put("medicine_id", medicineId.toString());
        payload.put("template", "DOSE_REMINDER_DUE");
        payload.put("channels", List.of("PUSH"));
        outbox.publish(
            DomainEvent.of(
                "medicine_schedule.notification.dose_reminder",
                "medicine_schedule",
                reminderId,
                payload));
      }

      @Override
      public void notifyRefillAlert(
          java.util.UUID customerId,
          java.util.UUID medicineId,
          int unitsInHand,
          int refillRemindAtUnits) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notification_id", Ids.newId().toString());
        payload.put("customer_id", customerId.toString());
        payload.put("medicine_id", medicineId.toString());
        payload.put("units_in_hand", unitsInHand);
        payload.put("refill_remind_at_units", refillRemindAtUnits);
        payload.put("category", "refill_reminders");
        payload.put("template", "REFILL_ALERT");
        payload.put("channels", List.of("PUSH"));
        outbox.publish(
            DomainEvent.of(
                "medicine_schedule.notification.refill_alert",
                "medicine_schedule",
                medicineId,
                payload));
      }
    };
  }
}
