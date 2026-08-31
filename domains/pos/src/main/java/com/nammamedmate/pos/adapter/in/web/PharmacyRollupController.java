package com.nammamedmate.pos.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.pos.application.PharmacyRollupService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pharmacy/rollup")
@Tag(name = "Pharmacy multi-branch rollup")
public class PharmacyRollupController {

  private final PharmacyRollupService rollup;

  public PharmacyRollupController(PharmacyRollupService rollup) {
    this.rollup = rollup;
  }

  @GetMapping("/summary")
  @Operation(summary = "Invoice totals across assigned pharmacies (Growth+ when 2+ shops)")
  public ApiResponse<Map<String, Object>> summary(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(rollup.summary(principal));
  }
}
