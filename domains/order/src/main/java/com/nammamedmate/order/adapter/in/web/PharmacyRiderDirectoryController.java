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
@RequestMapping("/api/v1/pharmacy/riders")
@Tag(name = "Pharmacy riders")
public class PharmacyRiderDirectoryController {

  private final OrderLifecycleService lifecycle;

  public PharmacyRiderDirectoryController(OrderLifecycleService lifecycle) {
    this.lifecycle = lifecycle;
  }

  @GetMapping
  @Operation(summary = "List ACTIVE riders for assignment")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(lifecycle.listRiders(principal));
  }
}
