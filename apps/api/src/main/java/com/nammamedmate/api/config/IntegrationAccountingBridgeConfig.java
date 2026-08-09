package com.nammamedmate.api.config;

import com.nammamedmate.crm.application.port.out.CrmPlanLookupPort;
import com.nammamedmate.crm.domain.PlanNames;
import com.nammamedmate.integration.application.port.out.AccountingDataPort;
import com.nammamedmate.integration.application.port.out.AccountingPlanPort;
import com.nammamedmate.integration.domain.AccountingVoucher;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Composition-root bridges for EPIC-022 STORY-005: CRM plan gate + optional POS/ERP voucher source.
 * Voucher source is empty until POS sales bridge is wired (unit tests inject fixtures).
 */
@Configuration
public class IntegrationAccountingBridgeConfig {

  private static final Set<String> ACCOUNTING_PLANS =
      Set.of(
          "GROWTH",
          PlanNames.RETAIL_PRO,
          PlanNames.ENTERPRISE,
          "PRO"); // legacy enterprise-tier alias

  @Bean
  @Primary
  AccountingPlanPort crmBackedAccountingPlanPort(CrmPlanLookupPort lookup) {
    return pharmacyId -> {
      String plan = lookup.planNameForPharmacy(pharmacyId).orElse(PlanNames.FREE);
      if (ACCOUNTING_PLANS.contains(plan)) {
        return true;
      }
      // RETAIL_PRO+ (growthFeaturesEnabled) covers CRM-canonical Growth mapping.
      return PlanNames.growthFeaturesEnabled(plan);
    };
  }

  @Bean
  @Primary
  AccountingDataPort stubAccountingDataPort() {
    return new AccountingDataPort() {
      @Override
      public List<AccountingVoucher> sales(UUID pharmacyId, LocalDate from, LocalDate to) {
        return List.of();
      }

      @Override
      public List<AccountingVoucher> purchases(UUID pharmacyId, LocalDate from, LocalDate to) {
        return List.of();
      }

      @Override
      public List<AccountingVoucher> expenses(UUID pharmacyId, LocalDate from, LocalDate to) {
        return List.of();
      }

      @Override
      public List<AccountingVoucher> gstEntries(UUID pharmacyId, LocalDate from, LocalDate to) {
        return List.of();
      }
    };
  }
}
