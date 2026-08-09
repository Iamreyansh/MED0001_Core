package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.port.out.CrmPlanLookupPort;
import com.nammamedmate.crm.domain.PlanNames;
import com.nammamedmate.integration.application.port.out.AccountingDataPort;
import com.nammamedmate.integration.application.port.out.AccountingPlanPort;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IntegrationAccountingBridgeConfigTest {

  @Test
  void planGateAcceptsGrowthRetailProEnterprise() {
    CrmPlanLookupPort lookup = mock(CrmPlanLookupPort.class);
    UUID pharmacyId = UUID.randomUUID();
    AccountingPlanPort gate =
        new IntegrationAccountingBridgeConfig().crmBackedAccountingPlanPort(lookup);

    when(lookup.planNameForPharmacy(pharmacyId)).thenReturn(Optional.of(PlanNames.FREE));
    assertThat(gate.allowsAccounting(pharmacyId)).isFalse();

    when(lookup.planNameForPharmacy(pharmacyId)).thenReturn(Optional.of(PlanNames.RETAIL_PRO));
    assertThat(gate.allowsAccounting(pharmacyId)).isTrue();
    when(lookup.planNameForPharmacy(pharmacyId)).thenReturn(Optional.of("GROWTH"));
    assertThat(gate.allowsAccounting(pharmacyId)).isTrue();
    when(lookup.planNameForPharmacy(pharmacyId)).thenReturn(Optional.of(PlanNames.ENTERPRISE));
    assertThat(gate.allowsAccounting(pharmacyId)).isTrue();
  }

  @Test
  void stubDataPortReturnsEmptyLists() {
    AccountingDataPort port = new IntegrationAccountingBridgeConfig().stubAccountingDataPort();
    UUID pharmacyId = UUID.randomUUID();
    LocalDate from = LocalDate.of(2026, 7, 1);
    LocalDate to = LocalDate.of(2026, 7, 31);
    assertThat(port.sales(pharmacyId, from, to)).isEmpty();
    assertThat(port.purchases(pharmacyId, from, to)).isEmpty();
    assertThat(port.expenses(pharmacyId, from, to)).isEmpty();
    assertThat(port.gstEntries(pharmacyId, from, to)).isEmpty();
  }
}
