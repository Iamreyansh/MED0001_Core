package com.nammamedmate.prescription.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.prescription.application.ScheduleDrugRegisterService;
import com.nammamedmate.prescription.application.ScheduleDrugRegisterService.ListResult;
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
@RequestMapping("/api/v1/pharmacy/compliance/drug-register")
@Tag(name = "Pharmacy Schedule H1/X drug register")
public class PharmacyDrugRegisterController {

  private final ScheduleDrugRegisterService service;

  public PharmacyDrugRegisterController(ScheduleDrugRegisterService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "View own pharmacy Schedule H1/X drug register")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(name = "schedule") String schedule,
      @RequestParam(name = "drug_name", required = false) String drugName,
      @RequestParam(name = "from_date", required = false) String fromDate,
      @RequestParam(name = "to_date", required = false) String toDate,
      @RequestParam(name = "page", required = false) Integer page,
      @RequestParam(name = "limit", required = false) Integer limit,
      @RequestParam(name = "export", required = false) Boolean export) {
    ListResult result =
        service.listPharmacy(principal, schedule, drugName, fromDate, toDate, page, limit, export);
    return ApiResponse.ok(result.data(), result.meta());
  }
}
