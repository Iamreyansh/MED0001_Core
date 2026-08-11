package com.nammamedmate.notification.adapter.out.persistence;

import com.nammamedmate.notification.application.port.out.CustomerPreferenceStore;
import com.nammamedmate.notification.domain.CustomerNotificationPreferences;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcCustomerPreferenceStore implements CustomerPreferenceStore {

  private final JdbcTemplate jdbc;

  public JdbcCustomerPreferenceStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<CustomerNotificationPreferences> findByCustomerId(UUID customerId) {
    List<CustomerNotificationPreferences> rows =
        jdbc.query(
            """
            SELECT id, customer_id, push_enabled, sms_enabled, whatsapp_enabled, email_enabled,
                   cat_order_updates, cat_account_critical, cat_promotions,
                   cat_refill_reminders, cat_offers, created_at, updated_at
            FROM customer_notification_preferences
            WHERE customer_id = ?
            LIMIT 1
            """,
            (rs, i) -> map(rs),
            customerId);
    return rows.stream().findFirst();
  }

  @Override
  public void insert(CustomerNotificationPreferences prefs) {
    jdbc.update(
        """
        INSERT INTO customer_notification_preferences (
          id, customer_id, push_enabled, sms_enabled, whatsapp_enabled, email_enabled,
          cat_order_updates, cat_account_critical, cat_promotions,
          cat_refill_reminders, cat_offers, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        prefs.id(),
        prefs.customerId(),
        prefs.pushEnabled(),
        prefs.smsEnabled(),
        prefs.whatsappEnabled(),
        prefs.emailEnabled(),
        prefs.catOrderUpdates(),
        prefs.catAccountCritical(),
        prefs.catPromotions(),
        prefs.catRefillReminders(),
        prefs.catOffers(),
        Timestamp.from(prefs.createdAt()),
        Timestamp.from(prefs.updatedAt()));
  }

  @Override
  public void update(CustomerNotificationPreferences prefs) {
    jdbc.update(
        """
        UPDATE customer_notification_preferences
        SET push_enabled = ?, sms_enabled = ?, whatsapp_enabled = ?, email_enabled = ?,
            cat_order_updates = ?, cat_account_critical = ?, cat_promotions = ?,
            cat_refill_reminders = ?, cat_offers = ?, updated_at = ?
        WHERE customer_id = ?
        """,
        prefs.pushEnabled(),
        prefs.smsEnabled(),
        prefs.whatsappEnabled(),
        prefs.emailEnabled(),
        prefs.catOrderUpdates(),
        prefs.catAccountCritical(),
        prefs.catPromotions(),
        prefs.catRefillReminders(),
        prefs.catOffers(),
        Timestamp.from(prefs.updatedAt()),
        prefs.customerId());
  }

  private static CustomerNotificationPreferences map(ResultSet rs) throws SQLException {
    return new CustomerNotificationPreferences(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("customer_id"),
        rs.getBoolean("push_enabled"),
        rs.getBoolean("sms_enabled"),
        rs.getBoolean("whatsapp_enabled"),
        rs.getBoolean("email_enabled"),
        rs.getBoolean("cat_order_updates"),
        rs.getBoolean("cat_account_critical"),
        rs.getBoolean("cat_promotions"),
        rs.getBoolean("cat_refill_reminders"),
        rs.getBoolean("cat_offers"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }
}
