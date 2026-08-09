package com.nammamedmate.pos.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.pos.application.KhataService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pharmacy/khata")
@Tag(name = "Pharmacy Khata")
public class PharmacyKhataController {

  private final KhataService khataService;

  public PharmacyKhataController(KhataService khataService) {
    this.khataService = khataService;
  }

  @GetMapping
  @Operation(summary = "Khata outstanding list with KPI and aging")
  public Object list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "overdue_only", required = false) Boolean overdueOnly,
      @RequestParam(value = "sort", required = false) String sort,
      @RequestParam(value = "q", required = false) String q,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "pharmacy_id", required = false) UUID pharmacyId) {
    KhataService.ListResult result =
        khataService.list(principal, overdueOnly, sort, q, page, limit, pharmacyId);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("success", true);
    body.put("data", result.data());
    body.put("meta", result.meta());
    return body;
  }

  @GetMapping("/payment-history")
  @Operation(summary = "Khata repayment payment history, or export EXCEL")
  public Object paymentHistory(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "from_date", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate fromDate,
      @RequestParam(value = "to_date", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate toDate,
      @RequestParam(value = "payment_mode", required = false) String paymentMode,
      @RequestParam(value = "q", required = false) String q,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "export", required = false) String export,
      @RequestParam(value = "pharmacy_id", required = false) UUID pharmacyId) {
    Object result =
        khataService.paymentHistory(
            principal, fromDate, toDate, paymentMode, q, page, limit, export, pharmacyId);
    if (result instanceof KhataService.FileExport file) {
      return ResponseEntity.ok()
          .header(
              HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
          .contentType(MediaType.parseMediaType(file.contentType()))
          .body(file.bytes());
    }
    KhataService.ListResult pageResult = (KhataService.ListResult) result;
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("success", true);
    body.put("data", pageResult.data());
    body.put("meta", pageResult.meta());
    return body;
  }

  @GetMapping("/{customerId}")
  @Operation(summary = "Customer khata ledger detail")
  public ApiResponse<Map<String, Object>> detail(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID customerId,
      @RequestParam(value = "pharmacy_id", required = false) UUID pharmacyId) {
    return ApiResponse.ok(khataService.detail(principal, customerId, pharmacyId));
  }

  @PostMapping("/{customerId}/repayment")
  @Operation(summary = "Record khata repayment")
  public ResponseEntity<ApiResponse<Map<String, Object>>> repay(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID customerId,
      @RequestBody(required = false) Map<String, Object> body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(khataService.repay(principal, customerId, body)));
  }

  @PostMapping("/{customerId}/remind")
  @Operation(summary = "Send khata payment reminder (owner)")
  public ApiResponse<Map<String, Object>> remind(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID customerId,
      @RequestBody(required = false) Map<String, Object> body) {
    return ApiResponse.ok(khataService.remind(principal, customerId, body));
  }
}
