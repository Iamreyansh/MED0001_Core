package com.nammamedmate.api.config;

import com.nammamedmate.crm.application.port.out.EnsureFreeSubscriptionPort;
import com.nammamedmate.crm.application.port.out.EnsureMarketplaceLeadPort;
import com.nammamedmate.crm.application.port.out.PharmacyPlanSyncPort;
import com.nammamedmate.crm.domain.PlanNames;
import com.nammamedmate.pharmacy.application.port.out.CrmAccountBootstrapPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Composition-root: pharmacy registration → CRM FREE subscription + MARKETPLACE lead; sync
 * pharmacies.plan from CRM.
 */
@Configuration
public class CrmPharmacyBridgeConfig {

  @Bean
  @Primary
  CrmAccountBootstrapPort crmAccountBootstrapPort(
      EnsureFreeSubscriptionPort ensure, EnsureMarketplaceLeadPort leads, JdbcTemplate jdbc) {
    return pharmacyId -> {
      ensure.ensureFreeSubscription(pharmacyId);
      createMarketplaceLead(leads, jdbc, pharmacyId);
    };
  }

  static void createMarketplaceLead(
      EnsureMarketplaceLeadPort leads, JdbcTemplate jdbc, UUID pharmacyId) {
    if (pharmacyId == null || leads == null || jdbc == null) {
      return;
    }
    try {
      Map<String, Object> row =
          jdbc.queryForMap(
              """
              SELECT COALESCE(NULLIF(TRIM(business_name), ''), name) AS pharmacy_name,
                     COALESCE(owner_name, 'Owner') AS contact_name,
                     phone, email
              FROM pharmacies
              WHERE id = ? AND deleted_at IS NULL
              """,
              pharmacyId);
      leads.ensureMarketplaceLead(
          pharmacyId,
          stringVal(row.get("pharmacy_name")),
          stringVal(row.get("contact_name")),
          stringVal(row.get("phone")),
          stringVal(row.get("email")));
    } catch (EmptyResultDataAccessException ignored) {
      // pharmacy row missing — skip lead
    }
  }

  private static String stringVal(Object v) {
    return v == null ? null : String.valueOf(v);
  }

  @Bean
  @Primary
  PharmacyPlanSyncPort jdbcPharmacyPlanSyncPort(JdbcTemplate jdbc) {
    return (pharmacyId, crmPlanName) -> {
      if (pharmacyId == null) {
        return;
      }
      String legacy = toLegacyPlan(crmPlanName);
      Instant now = Instant.now();
      jdbc.update(
          """
          UPDATE pharmacies SET plan = ?, subscription_plan = ?, updated_at = ?
          WHERE id = ? AND deleted_at IS NULL
          """,
          legacy,
          legacy,
          Timestamp.from(now),
          pharmacyId);
    };
  }

  static String toLegacyPlan(String crmPlanName) {
    if (crmPlanName == null || crmPlanName.isBlank()) {
      return PlanNames.FREE;
    }
    return switch (crmPlanName.trim().toUpperCase(Locale.ROOT)) {
      case "RETAIL_PRO" -> "GROWTH";
      case "ENTERPRISE" -> "PRO";
      case "STARTER" -> "STARTER";
      case "FREE" -> "FREE";
      default -> crmPlanName.trim().toUpperCase(Locale.ROOT);
    };
  }
}
