package com.nammamedmate.api.config;

import com.nammamedmate.analytics.application.port.out.AnalyticsPlanPort;
import com.nammamedmate.crm.application.port.out.CrmPlanLookupPort;
import com.nammamedmate.crm.domain.PlanNames;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Composition-root: pharmacy analytics Growth+ gate via CRM plan lookup (no domain→domain deps).
 */
@Configuration
public class AnalyticsPlanBridgeConfig {

  private static final Set<String> ANALYTICS_PLANS =
      Set.of(
          "GROWTH",
          PlanNames.RETAIL_PRO,
          PlanNames.ENTERPRISE,
          "PRO"); // legacy enterprise-tier alias

  @Bean
  @Primary
  AnalyticsPlanPort crmBackedAnalyticsPlanPort(CrmPlanLookupPort lookup) {
    return pharmacyId -> {
      String plan = lookup.planNameForPharmacy(pharmacyId).orElse(PlanNames.FREE);
      if (ANALYTICS_PLANS.contains(plan)) {
        return true;
      }
      return PlanNames.growthFeaturesEnabled(plan);
    };
  }
}
