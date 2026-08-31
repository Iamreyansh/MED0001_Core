package com.nammamedmate.order.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.order.application.OrderLifecycleService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pharmacy/dashboard")
@Tag(name = "Pharmacy dashboard")
public class PharmacyDashboardController {

  private final OrderLifecycleService lifecycle;

  public PharmacyDashboardController(OrderLifecycleService lifecycle) {
    this.lifecycle = lifecycle;
  }

  @GetMapping("/summary")
  @Operation(summary = "Order KPI counts for the home console")
  public ApiResponse<Map<String, Object>> summary(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(lifecycle.dashboardSummary(principal));
  }
}
