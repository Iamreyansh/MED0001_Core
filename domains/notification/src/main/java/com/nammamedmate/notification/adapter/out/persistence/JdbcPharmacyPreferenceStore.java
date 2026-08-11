package com.nammamedmate.notification.adapter.out.persistence;

import com.nammamedmate.notification.application.port.out.PharmacyPreferenceStore;
import com.nammamedmate.notification.domain.PharmacyNotificationPreferences;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcPharmacyPreferenceStore implements PharmacyPreferenceStore {

  private final JdbcTemplate jdbc;

  public JdbcPharmacyPreferenceStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<PharmacyNotificationPreferences> findByPharmacyId(UUID pharmacyId) {
    List<PharmacyNotificationPreferences> rows =
        jdbc.query(
            """
            SELECT id, pharmacy_id, push_enabled, sms_enabled, whatsapp_enabled, email_enabled,
                   cat_order_alerts, cat_settlement_updates, cat_kyc_updates,
                   cat_low_stock_alerts, cat_compliance_reminders, created_at, updated_at
            FROM pharmacy_notification_preferences
            WHERE pharmacy_id = ?
            LIMIT 1
            """,
            (rs, i) -> map(rs),
            pharmacyId);
    return rows.stream().findFirst();
  }

  @Override
  public void insert(PharmacyNotificationPreferences prefs) {
    jdbc.update(
        """
        INSERT INTO pharmacy_notification_preferences (
          id, pharmacy_id, push_enabled, sms_enabled, whatsapp_enabled, email_enabled,
          cat_order_alerts, cat_settlement_updates, cat_kyc_updates,
          cat_low_stock_alerts, cat_compliance_reminders, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        prefs.id(),
        prefs.pharmacyId(),
        prefs.pushEnabled(),
        prefs.smsEnabled(),
        prefs.whatsappEnabled(),
        prefs.emailEnabled(),
        prefs.catOrderAlerts(),
        prefs.catSettlementUpdates(),
        prefs.catKycUpdates(),
        prefs.catLowStockAlerts(),
        prefs.catComplianceReminders(),
        Timestamp.from(prefs.createdAt()),
        Timestamp.from(prefs.updatedAt()));
  }

  @Override
  public void update(PharmacyNotificationPreferences prefs) {
    jdbc.update(
        """
        UPDATE pharmacy_notification_preferences
        SET push_enabled = ?, sms_enabled = ?, whatsapp_enabled = ?, email_enabled = ?,
            cat_order_alerts = ?, cat_settlement_updates = ?, cat_kyc_updates = ?,
            cat_low_stock_alerts = ?, cat_compliance_reminders = ?, updated_at = ?
        WHERE pharmacy_id = ?
        """,
        prefs.pushEnabled(),
        prefs.smsEnabled(),
        prefs.whatsappEnabled(),
        prefs.emailEnabled(),
        prefs.catOrderAlerts(),
        prefs.catSettlementUpdates(),
        prefs.catKycUpdates(),
        prefs.catLowStockAlerts(),
        prefs.catComplianceReminders(),
        Timestamp.from(prefs.updatedAt()),
        prefs.pharmacyId());
  }

  private static PharmacyNotificationPreferences map(ResultSet rs) throws SQLException {
    return new PharmacyNotificationPreferences(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("pharmacy_id"),
        rs.getBoolean("push_enabled"),
        rs.getBoolean("sms_enabled"),
        rs.getBoolean("whatsapp_enabled"),
        rs.getBoolean("email_enabled"),
        rs.getBoolean("cat_order_alerts"),
        rs.getBoolean("cat_settlement_updates"),
        rs.getBoolean("cat_kyc_updates"),
        rs.getBoolean("cat_low_stock_alerts"),
        rs.getBoolean("cat_compliance_reminders"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }
}
