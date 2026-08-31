package com.nammamedmate.pos.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.pos.application.PharmacyRollupService;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PharmacyRollupControllerTest {

  @Test
  void delegates() {
    PharmacyRollupService rollup = mock(PharmacyRollupService.class);
    PharmacyRollupController controller = new PharmacyRollupController(rollup);
    MedmatePrincipal owner =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, UUID.randomUUID(), TokenScope.FULL, "j");
    when(rollup.summary(owner)).thenReturn(Map.of("pharmacy_count", 1));
    assertThat(controller.summary(owner).data().get("pharmacy_count")).isEqualTo(1);
  }
}
