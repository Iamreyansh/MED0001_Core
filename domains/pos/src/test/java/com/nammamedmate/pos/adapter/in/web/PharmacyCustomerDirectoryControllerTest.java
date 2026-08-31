package com.nammamedmate.pos.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.pos.application.PharmacyCustomerDirectoryService;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PharmacyCustomerDirectoryControllerTest {

  @Test
  void delegates() {
    PharmacyCustomerDirectoryService service = mock(PharmacyCustomerDirectoryService.class);
    PharmacyCustomerDirectoryController controller =
        new PharmacyCustomerDirectoryController(service);
    MedmatePrincipal owner =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, UUID.randomUUID(), TokenScope.FULL, "j");
    when(service.list(owner, "q", 1, 20))
        .thenReturn(
            new PharmacyCustomerDirectoryService.ListResult(
                Map.of("customers", java.util.List.of()), PaginationMeta.of(1, 20, 0)));
    assertThat(controller.list(owner, "q", 1, 20).success()).isTrue();
  }
}
