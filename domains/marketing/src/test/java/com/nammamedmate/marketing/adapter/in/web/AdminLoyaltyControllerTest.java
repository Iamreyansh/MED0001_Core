package com.nammamedmate.marketing.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.marketing.application.port.out.LoyaltyAdminPort;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminLoyaltyControllerTest {

  @Mock private LoyaltyAdminPort loyalty;
  private AdminLoyaltyController controller;
  private final MedmatePrincipal admin =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    controller = new AdminLoyaltyController(loyalty);
  }

  @Test
  void getPatchOverviewAdjust() {
    when(loyalty.getProgram(admin)).thenReturn(Map.of("earn_rate_rs_per_point", 100));
    assertThat(controller.getProgram(admin).data()).containsEntry("earn_rate_rs_per_point", 100);

    when(loyalty.patchProgram(eq(admin), any())).thenReturn(Map.of("updated_by", admin.subject()));
    ApiResponse<Map<String, Object>> patched =
        controller.patchProgram(
            admin,
            new AdminLoyaltyController.PatchProgramRequest(
                100, null, null, null, null, 25, null, null));
    assertThat(patched.data()).containsKey("updated_by");
    assertThat(controller.patchProgram(admin, null).data()).containsKey("updated_by");

    when(loyalty.overview(admin)).thenReturn(Map.of("total_points_outstanding", 1));
    assertThat(controller.overview(admin).data()).containsEntry("total_points_outstanding", 1);

    UUID customerId = Ids.newId();
    when(loyalty.adjust(eq(admin), eq(customerId), any()))
        .thenReturn(Map.of("points_adjusted", 50));
    assertThat(
            controller
                .adjust(
                    admin,
                    customerId.toString(),
                    new AdminLoyaltyController.AdjustRequest(50, "reason", null))
                .data())
        .containsEntry("points_adjusted", 50);

    assertThatThrownBy(() -> controller.adjust(admin, "bad", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThat(controller.adjust(admin, customerId.toString(), null).data())
        .containsEntry("points_adjusted", 50);
  }
}
