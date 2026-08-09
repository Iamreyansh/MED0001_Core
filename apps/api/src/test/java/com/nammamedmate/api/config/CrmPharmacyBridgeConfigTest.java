package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.port.out.EnsureFreeSubscriptionPort;
import com.nammamedmate.crm.application.port.out.EnsureMarketplaceLeadPort;
import com.nammamedmate.crm.application.port.out.PharmacyPlanSyncPort;
import com.nammamedmate.crm.domain.PlanNames;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.pharmacy.application.port.out.CrmAccountBootstrapPort;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class CrmPharmacyBridgeConfigTest {

  @Test
  void mapsLegacyPlansAndWiresBeans() {
    assertThat(CrmPharmacyBridgeConfig.toLegacyPlan(PlanNames.RETAIL_PRO)).isEqualTo("GROWTH");
    assertThat(CrmPharmacyBridgeConfig.toLegacyPlan(PlanNames.ENTERPRISE)).isEqualTo("PRO");
    assertThat(CrmPharmacyBridgeConfig.toLegacyPlan(PlanNames.STARTER)).isEqualTo("STARTER");
    assertThat(CrmPharmacyBridgeConfig.toLegacyPlan(null)).isEqualTo(PlanNames.FREE);
    assertThat(CrmPharmacyBridgeConfig.toLegacyPlan("CUSTOM")).isEqualTo("CUSTOM");

    EnsureFreeSubscriptionPort ensure = mock(EnsureFreeSubscriptionPort.class);
    EnsureMarketplaceLeadPort leads = mock(EnsureMarketplaceLeadPort.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    UUID pharmacyId = Ids.newId();
    when(jdbc.queryForMap(anyString(), eq(pharmacyId)))
        .thenReturn(
            Map.of(
                "pharmacy_name",
                "Sri Ram",
                "contact_name",
                "Ramesh",
                "phone",
                "+9198",
                "email",
                "r@x.com"));

    CrmAccountBootstrapPort bootstrap =
        new CrmPharmacyBridgeConfig().crmAccountBootstrapPort(ensure, leads, jdbc);
    bootstrap.ensureFreeSubscription(pharmacyId);
    verify(ensure).ensureFreeSubscription(pharmacyId);
    verify(leads).ensureMarketplaceLead(pharmacyId, "Sri Ram", "Ramesh", "+9198", "r@x.com");

    when(jdbc.queryForMap(anyString(), eq(pharmacyId)))
        .thenThrow(new EmptyResultDataAccessException(1));
    bootstrap.ensureFreeSubscription(pharmacyId);

    CrmPharmacyBridgeConfig.createMarketplaceLead(leads, jdbc, null);
    CrmPharmacyBridgeConfig.createMarketplaceLead(null, jdbc, pharmacyId);
    CrmPharmacyBridgeConfig.createMarketplaceLead(leads, null, pharmacyId);

    PharmacyPlanSyncPort sync = new CrmPharmacyBridgeConfig().jdbcPharmacyPlanSyncPort(jdbc);
    sync.syncPlan(pharmacyId, PlanNames.RETAIL_PRO);
    verify(jdbc).update(anyString(), eq("GROWTH"), eq("GROWTH"), any(), eq(pharmacyId));
    sync.syncPlan(null, PlanNames.FREE);
  }
}
