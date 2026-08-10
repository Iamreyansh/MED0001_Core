package com.nammamedmate.marketing.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.marketing.application.CouponService;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class AdminCouponControllerTest {

  @Mock CouponService coupons;
  @InjectMocks AdminCouponController controller;

  private final MedmatePrincipal admin =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");

  @Test
  void listCreateGetPatchToggleDelete() {
    when(coupons.list(any(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
        .thenReturn(
            new CouponService.PagedResult(
                Map.of("coupons", java.util.List.of()), PaginationMeta.of(1, 20, 0)));
    assertThat(controller.list(admin, null, null, null, null, null, null).success()).isTrue();

    when(coupons.create(any(), any())).thenReturn(Map.of("code", "X"));
    assertThat(controller.create(admin, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);

    when(coupons.get(any(), eq("X"), isNull(), isNull())).thenReturn(Map.of("code", "X"));
    assertThat(controller.get(admin, "X", null, null).data().get("code")).isEqualTo("X");

    when(coupons.patch(any(), eq("X"), any())).thenReturn(Map.of("code", "X"));
    assertThat(
            controller
                .patch(
                    admin,
                    "X",
                    new AdminCouponController.PatchCouponRequest(
                        "X", null, null, null, null, null, null, null, null, null, null, null, null,
                        null))
                .success())
        .isTrue();

    assertThat(
            controller
                .patch(
                    admin,
                    "X",
                    new AdminCouponController.PatchCouponRequest(
                        null,
                        "PERCENTAGE",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null))
                .success())
        .isTrue();

    when(coupons.toggle(any(), eq("X"))).thenReturn(Map.of("status", "PAUSED"));
    assertThat(controller.toggle(admin, "X").data().get("status")).isEqualTo("PAUSED");

    when(coupons.delete(any(), eq("X"))).thenReturn(Map.of("action", "DELETED"));
    assertThat(controller.delete(admin, "X").data().get("action")).isEqualTo("DELETED");
    verify(coupons).delete(any(), eq("X"));
  }
}
