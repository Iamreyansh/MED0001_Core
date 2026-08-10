package com.nammamedmate.marketing.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.marketing.application.BannerService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/banners")
@Tag(name = "Customer banners")
public class CustomerBannerController {

  private final BannerService banners;

  public CustomerBannerController(BannerService banners) {
    this.banners = banners;
  }

  @GetMapping
  @Operation(summary = "List active banners for placement")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam String placement,
      @RequestParam(required = false) Double lat,
      @RequestParam(required = false) Double lng) {
    // lat/lng reserved for future geo-filtering
    return ApiResponse.ok(banners.listCustomer(principal, placement));
  }

  @PostMapping("/{id}/impression")
  @Operation(summary = "Log banner impression (throttled)")
  public ApiResponse<Map<String, Object>> impression(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
    return ApiResponse.ok(banners.logImpression(principal, id, sessionId));
  }

  @PostMapping("/{id}/click")
  @Operation(summary = "Log banner click")
  public ApiResponse<Map<String, Object>> click(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(banners.logClick(principal, id));
  }
}
