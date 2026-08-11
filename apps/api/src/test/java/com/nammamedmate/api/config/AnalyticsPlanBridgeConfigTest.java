package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.analytics.application.port.out.AnalyticsPlanPort;
import com.nammamedmate.crm.application.port.out.CrmPlanLookupPort;
import com.nammamedmate.crm.domain.PlanNames;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnalyticsPlanBridgeConfigTest {

  @Test
  void gateAcceptsGrowthRetailProEnterpriseRejectsFreeStarter() {
    CrmPlanLookupPort lookup = mock(CrmPlanLookupPort.class);
    UUID pharmacyId = UUID.randomUUID();
    AnalyticsPlanPort gate = new AnalyticsPlanBridgeConfig().crmBackedAnalyticsPlanPort(lookup);

    when(lookup.planNameForPharmacy(pharmacyId)).thenReturn(Optional.of(PlanNames.FREE));
    assertThat(gate.allowsPharmacyAnalytics(pharmacyId)).isFalse();
    when(lookup.planNameForPharmacy(pharmacyId)).thenReturn(Optional.of(PlanNames.STARTER));
    assertThat(gate.allowsPharmacyAnalytics(pharmacyId)).isFalse();
    when(lookup.planNameForPharmacy(pharmacyId)).thenReturn(Optional.of("GROWTH"));
    assertThat(gate.allowsPharmacyAnalytics(pharmacyId)).isTrue();
    when(lookup.planNameForPharmacy(pharmacyId)).thenReturn(Optional.of(PlanNames.RETAIL_PRO));
    assertThat(gate.allowsPharmacyAnalytics(pharmacyId)).isTrue();
    when(lookup.planNameForPharmacy(pharmacyId)).thenReturn(Optional.of(PlanNames.ENTERPRISE));
    assertThat(gate.allowsPharmacyAnalytics(pharmacyId)).isTrue();
  }
}
