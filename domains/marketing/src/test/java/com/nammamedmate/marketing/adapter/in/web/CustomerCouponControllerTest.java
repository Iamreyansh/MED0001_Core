package com.nammamedmate.marketing.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

@ExtendWith(MockitoExtension.class)
class CustomerCouponControllerTest {

  @Mock CouponService coupons;
  @InjectMocks CustomerCouponController controller;

  private final MedmatePrincipal customer =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  @Test
  void validateAndAvailable() {
    when(coupons.validate(any(), any())).thenReturn(Map.of("valid", true));
    assertThat(controller.validate(customer, null).data().get("valid")).isEqualTo(true);
    assertThat(
            controller
                .validate(
                    customer,
                    new CustomerCouponController.ValidateRequest(
                        "NAMMA25", 580, customer.subject(), false, false, null))
                .data()
                .get("valid"))
        .isEqualTo(true);

    when(coupons.available(any(), eq(false)))
        .thenReturn(
            new CouponService.PagedResult(
                Map.of("coupons", java.util.List.of()), PaginationMeta.of(1, 0, 0)));
    assertThat(controller.available(customer, false).success()).isTrue();
  }
}
