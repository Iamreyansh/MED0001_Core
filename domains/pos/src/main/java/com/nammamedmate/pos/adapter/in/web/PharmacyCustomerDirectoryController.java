package com.nammamedmate.pos.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.pos.application.PharmacyCustomerDirectoryService;
import com.nammamedmate.pos.application.PharmacyCustomerDirectoryService.ListResult;
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
@RequestMapping("/api/v1/pharmacy/customers")
@Tag(name = "Pharmacy customers")
public class PharmacyCustomerDirectoryController {

  private final PharmacyCustomerDirectoryService customers;

  public PharmacyCustomerDirectoryController(PharmacyCustomerDirectoryService customers) {
    this.customers = customers;
  }

  @GetMapping
  @Operation(summary = "Distinct POS/khata customers for the active pharmacy")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "q", required = false) String q,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "limit", required = false) Integer limit) {
    ListResult result = customers.list(principal, q, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }
}
