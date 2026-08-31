package com.nammamedmate.inventory.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.inventory.application.SupplierRtvService;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PharmacyRtvControllerTest {

  @Test
  void delegates() {
    SupplierRtvService rtv = mock(SupplierRtvService.class);
    PharmacyRtvController controller = new PharmacyRtvController(rtv);
    MedmatePrincipal owner =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, UUID.randomUUID(), TokenScope.FULL, "j");
    UUID grnId = UUID.randomUUID();
    when(rtv.create(owner, grnId, null, null)).thenReturn(Map.of("ok", true));
    assertThat(controller.create(owner, grnId, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    when(rtv.create(owner, grnId, "expired", List.of())).thenReturn(Map.of("rtv_id", "1"));
    assertThat(
            controller
                .create(owner, grnId, new PharmacyRtvController.RtvRequest("expired", List.of()))
                .getBody()
                .data()
                .get("rtv_id"))
        .isEqualTo("1");
  }
}
