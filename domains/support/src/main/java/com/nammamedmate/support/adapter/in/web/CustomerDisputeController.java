package com.nammamedmate.support.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.support.application.DisputeService;
import com.nammamedmate.support.application.DisputeService.ListResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customers/me/disputes")
@Tag(name = "Customer disputes")
public class CustomerDisputeController {

  private final DisputeService disputes;

  public CustomerDisputeController(DisputeService disputes) {
    this.disputes = disputes;
  }

  @GetMapping
  @Operation(summary = "List own disputes")
  public ApiResponse<Map<String, Object>> listMine(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    ListResult result = disputes.listMine(principal, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }
}
