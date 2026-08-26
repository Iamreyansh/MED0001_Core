package com.nammamedmate.notification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.notification.domain.CustomerNotificationPreferences;
import com.nammamedmate.notification.domain.PharmacyNotificationPreferences;
import com.nammamedmate.notification.domain.PreferenceAuditEntry;
import com.nammamedmate.notification.domain.PreferenceChangeSource;
import com.nammamedmate.notification.domain.PreferenceEntityType;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcPreferenceStoresTest {

  private static final Instant NOW = Instant.parse("2026-08-11T08:00:00Z");
  private static final UUID ID = UUID.fromString("f1000001-0000-4000-8000-000000000001");
  private static final UUID CUST = UUID.fromString("c1000001-0000-4000-8000-000000000001");
  private static final UUID PHARM = UUID.fromString("a1000001-0000-4000-8000-0000000000aa");

  @Test
  @SuppressWarnings("unchecked")
  void customerPharmacyAuditAndIdentityStores() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ObjectMapper om = new ObjectMapper().findAndRegisterModules();
    JdbcCustomerPreferenceStore customers = new JdbcCustomerPreferenceStore(jdbc);
    JdbcPharmacyPreferenceStore pharmacies = new JdbcPharmacyPreferenceStore(jdbc);
    JdbcPreferenceAuditStore audits = new JdbcPreferenceAuditStore(jdbc, om);
    JdbcRecipientIdentityPort identities = new JdbcRecipientIdentityPort(jdbc);

    when(jdbc.query(contains("customer_notification_preferences"), any(RowMapper.class), any()))
        .thenReturn(List.of());
    assertThat(customers.findByCustomerId(CUST)).isEmpty();

    when(jdbc.query(contains("customer_notification_preferences"), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<CustomerNotificationPreferences> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(ID);
              when(rs.getObject("customer_id")).thenReturn(CUST);
              when(rs.getBoolean("push_enabled")).thenReturn(true);
              when(rs.getBoolean("sms_enabled")).thenReturn(true);
              when(rs.getBoolean("whatsapp_enabled")).thenReturn(true);
              when(rs.getBoolean("email_enabled")).thenReturn(true);
              when(rs.getBoolean("cat_order_updates")).thenReturn(true);
              when(rs.getBoolean("cat_account_critical")).thenReturn(true);
              when(rs.getBoolean("cat_promotions")).thenReturn(true);
              when(rs.getBoolean("cat_refill_reminders")).thenReturn(true);
              when(rs.getBoolean("cat_offers")).thenReturn(false);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(NOW));
              return List.of(mapper.mapRow(rs, 0));
            });
    CustomerNotificationPreferences c = customers.findByCustomerId(CUST).orElseThrow();
    assertThat(c.catOffers()).isFalse();
    assertThat(c.channelEnabled("push")).isTrue();
    assertThat(c.channelEnabled(null)).isTrue();
    assertThat(c.channelEnabled("other")).isTrue();
    assertThat(c.categoryEnabled("promotions")).isTrue();
    assertThat(c.categoryEnabled(null)).isTrue();
    assertThat(c.categoryEnabled("unknown")).isTrue();

    when(jdbc.update(
            contains("INSERT INTO customer_notification_preferences"),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(1);
    customers.insert(CustomerNotificationPreferences.defaults(ID, CUST, NOW));
    when(jdbc.update(
            contains("UPDATE customer_notification_preferences"),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(1);
    customers.update(c);

    when(jdbc.query(contains("pharmacy_notification_preferences"), any(RowMapper.class), any()))
        .thenReturn(List.of());
    assertThat(pharmacies.findByPharmacyId(PHARM)).isEmpty();
    when(jdbc.query(contains("pharmacy_notification_preferences"), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<PharmacyNotificationPreferences> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(ID);
              when(rs.getObject("pharmacy_id")).thenReturn(PHARM);
              when(rs.getBoolean("push_enabled")).thenReturn(true);
              when(rs.getBoolean("sms_enabled")).thenReturn(false);
              when(rs.getBoolean("whatsapp_enabled")).thenReturn(true);
              when(rs.getBoolean("email_enabled")).thenReturn(true);
              when(rs.getBoolean("cat_order_alerts")).thenReturn(true);
              when(rs.getBoolean("cat_settlement_updates")).thenReturn(true);
              when(rs.getBoolean("cat_kyc_updates")).thenReturn(true);
              when(rs.getBoolean("cat_low_stock_alerts")).thenReturn(true);
              when(rs.getBoolean("cat_compliance_reminders")).thenReturn(true);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(NOW));
              return List.of(mapper.mapRow(rs, 0));
            });
    PharmacyNotificationPreferences p = pharmacies.findByPharmacyId(PHARM).orElseThrow();
    assertThat(p.smsEnabled()).isFalse();
    assertThat(p.channelEnabled("sms")).isFalse();
    assertThat(p.categoryEnabled("order_alerts")).isTrue();
    assertThat(p.categoryEnabled(" ")).isTrue();

    when(jdbc.update(
            contains("INSERT INTO pharmacy_notification_preferences"),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(1);
    pharmacies.insert(PharmacyNotificationPreferences.defaults(ID, PHARM, NOW));
    when(jdbc.update(
            contains("UPDATE pharmacy_notification_preferences"),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(1);
    pharmacies.update(p);

    when(jdbc.update(
            contains("notification_preference_audit"),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(1);
    audits.insert(
        new PreferenceAuditEntry(
            Ids.newId(),
            PreferenceEntityType.CUSTOMER,
            CUST,
            CUST,
            PreferenceChangeSource.USER,
            Map.of("a", 1),
            Map.of("b", 2),
            NOW));

    ObjectMapper failing =
        new ObjectMapper() {
          @Override
          public String writeValueAsString(Object value)
              throws com.fasterxml.jackson.core.JsonProcessingException {
            throw new com.fasterxml.jackson.core.JsonProcessingException("x") {};
          }
        };
    JdbcPreferenceAuditStore badAudits = new JdbcPreferenceAuditStore(jdbc, failing);
    assertThatThrownBy(
            () ->
                badAudits.insert(
                    new PreferenceAuditEntry(
                        Ids.newId(),
                        PreferenceEntityType.CUSTOMER,
                        CUST,
                        null,
                        PreferenceChangeSource.SYSTEM,
                        Map.of(),
                        Map.of(),
                        NOW)))
        .isInstanceOf(IllegalStateException.class);

    assertThat(identities.findCustomerIdByPhone(null)).isEmpty();
    assertThat(identities.findCustomerIdByPhone(" ")).isEmpty();
    assertThat(identities.findPhoneByCustomerId(null)).isEmpty();

    when(jdbc.query(contains("SELECT id FROM customers"), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<UUID> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(CUST);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(identities.findCustomerIdByPhone("+91")).contains(CUST);
    when(jdbc.query(contains("SELECT phone FROM customers"), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<String> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("phone")).thenReturn("+91");
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(identities.findPhoneByCustomerId(CUST)).contains("+91");
    when(jdbc.query(contains("SELECT id FROM customers"), any(RowMapper.class), any()))
        .thenReturn(List.of());
    assertThat(identities.findCustomerIdByPhone("+99")).isEmpty();

    UUID riderId = UUID.fromString("d1000001-0000-4000-8000-00000000000d");
    assertThat(identities.findPhoneByRiderId(null)).isEmpty();
    when(jdbc.query(contains("SELECT phone FROM riders"), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<String> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("phone")).thenReturn("+919800011122");
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(identities.findPhoneByRiderId(riderId)).contains("+919800011122");
    when(jdbc.query(contains("SELECT phone FROM riders"), any(RowMapper.class), any()))
        .thenReturn(List.of());
    assertThat(identities.findPhoneByRiderId(riderId)).isEmpty();

    when(jdbc.update(
            contains("notification_preference_audit"),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(1);
    audits.insert(
        new PreferenceAuditEntry(
            Ids.newId(),
            PreferenceEntityType.CUSTOMER,
            CUST,
            null,
            PreferenceChangeSource.SYSTEM,
            null,
            null,
            NOW));
  }
}
