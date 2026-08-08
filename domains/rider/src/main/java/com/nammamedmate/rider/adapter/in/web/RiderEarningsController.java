package com.nammamedmate.rider.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.rider.application.RiderEarningsService;
import com.nammamedmate.rider.application.RiderEarningsService.TripsResult;
import com.nammamedmate.rider.application.RiderPerformanceService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rider")
@Tag(name = "Rider earnings & performance")
public class RiderEarningsController {

  private final RiderEarningsService earnings;
  private final RiderPerformanceService performance;

  public RiderEarningsController(
      RiderEarningsService earnings, RiderPerformanceService performance) {
    this.earnings = earnings;
    this.performance = performance;
  }

  @GetMapping("/earnings")
  @Operation(summary = "Rider: earnings dashboard")
  public ApiResponse<Map<String, Object>> earnings(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(earnings.dashboard(principal));
  }

  @GetMapping("/performance")
  @Operation(summary = "Rider: own performance metrics")
  public ApiResponse<Map<String, Object>> performance(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(performance.riderPerformance(principal));
  }

  @GetMapping("/trips")
  @Operation(summary = "Rider: trip history with earnings")
  public ApiResponse<Map<String, Object>> trips(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    TripsResult result = earnings.trips(principal, page, limit, from, to);
    return ApiResponse.ok(result.data(), result.meta());
  }
}
