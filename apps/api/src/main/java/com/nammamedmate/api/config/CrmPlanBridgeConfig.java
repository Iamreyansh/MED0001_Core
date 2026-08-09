package com.nammamedmate.api.config;

import com.nammamedmate.crm.application.port.out.CrmPlanLookupPort;
import com.nammamedmate.crm.domain.PlanNames;
import com.nammamedmate.inventory.application.port.out.InventoryPlanPort;
import com.nammamedmate.pos.application.port.out.PosPlanPort;
import com.nammamedmate.security.PharmacyContext;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Composition-root: POS/Inventory plan gates read crm_account.current_plan_name via CRM lookup.
 * Legacy pharmacies.plan mapping (RETAIL_PRO↔GROWTH, ENTERPRISE↔PRO) is applied at account
 * backfill; runtime gates use CRM plan names.
 */
@Configuration
public class CrmPlanBridgeConfig {

  @Bean
  @Primary
  PosPlanPort crmBackedPosPlanPort(CrmPlanLookupPort lookup) {
    return new PosPlanPort() {
      @Override
      public boolean starterFeaturesEnabled() {
        return PlanNames.starterFeaturesEnabled(currentPlan(lookup));
      }

      @Override
      public boolean growthFeaturesEnabled() {
        return PlanNames.growthFeaturesEnabled(currentPlan(lookup));
      }
    };
  }

  @Bean
  @Primary
  InventoryPlanPort crmBackedInventoryPlanPort(CrmPlanLookupPort lookup) {
    return () -> PlanNames.growthFeaturesEnabled(currentPlan(lookup));
  }

  private static String currentPlan(CrmPlanLookupPort lookup) {
    UUID pharmacyId = PharmacyContext.currentPharmacyId().orElse(null);
    if (pharmacyId == null) {
      return PlanNames.FREE;
    }
    return lookup.planNameForPharmacy(pharmacyId).orElse(PlanNames.FREE);
  }
}
