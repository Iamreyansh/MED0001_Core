package com.nammamedmate.prescription.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.prescription.application.ScheduleDrugRegisterService;
import com.nammamedmate.prescription.application.ScheduleDrugRegisterService.ListResult;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PharmacyDrugRegisterControllerTest {

  @Test
  void delegates() {
    ScheduleDrugRegisterService service = mock(ScheduleDrugRegisterService.class);
    PharmacyDrugRegisterController controller = new PharmacyDrugRegisterController(service);
    MedmatePrincipal owner =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, UUID.randomUUID(), TokenScope.FULL, "j");
    when(service.listPharmacy(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new ListResult(Map.of("entries", java.util.List.of()), PaginationMeta.of(1, 50, 0)));
    assertThat(controller.list(owner, "H1", null, null, null, 1, 50, false).success()).isTrue();
  }
}
