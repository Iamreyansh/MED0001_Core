package com.nammamedmate.pos.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.pos.application.InvoiceService;
import com.nammamedmate.pos.application.SalesLedgerService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
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
@RequestMapping("/api/v1/pharmacy/sales")
@Tag(name = "Pharmacy Sales Ledger")
public class PharmacySalesController {

  private final SalesLedgerService salesLedgerService;

  public PharmacySalesController(SalesLedgerService salesLedgerService) {
    this.salesLedgerService = salesLedgerService;
  }

  @GetMapping
  @Operation(summary = "Sales ledger list with period summary, or export EXCEL/PDF")
  public Object list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "from_date", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate fromDate,
      @RequestParam(value = "to_date", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate toDate,
      @RequestParam(value = "channel", required = false) String channel,
      @RequestParam(value = "payment_method", required = false) String paymentMethod,
      @RequestParam(value = "payment_status", required = false) String paymentStatus,
      @RequestParam(value = "q", required = false) String q,
      @RequestParam(value = "sort", required = false) String sort,
      @RequestParam(value = "order", required = false) String order,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "export", required = false) String export,
      @RequestParam(value = "financial_year", required = false) String financialYear,
      @RequestParam(value = "pharmacy_id", required = false) UUID pharmacyId) {
    Object result =
        salesLedgerService.list(
            principal,
            fromDate,
            toDate,
            channel,
            paymentMethod,
            paymentStatus,
            q,
            sort,
            order,
            page,
            limit,
            export,
            financialYear,
            pharmacyId);
    if (result instanceof InvoiceService.FileExport file) {
      return ResponseEntity.ok()
          .header(
              HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
          .contentType(MediaType.parseMediaType(file.contentType()))
          .body(file.bytes());
    }
    SalesLedgerService.ListResult pageResult = (SalesLedgerService.ListResult) result;
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("success", true);
    body.put("data", pageResult.data());
    body.put("meta", pageResult.meta());
    return body;
  }

  @GetMapping("/summary")
  @Operation(summary = "Sales analytics summary for date range")
  public ApiResponse<Map<String, Object>> summary(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "from_date", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate fromDate,
      @RequestParam(value = "to_date", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate toDate,
      @RequestParam(value = "pharmacy_id", required = false) UUID pharmacyId) {
    return ApiResponse.ok(salesLedgerService.summary(principal, fromDate, toDate, pharmacyId));
  }

  @GetMapping("/{saleId}")
  @Operation(summary = "Sale detail (alias of invoice detail)")
  public ApiResponse<Map<String, Object>> detail(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable UUID saleId) {
    return ApiResponse.ok(salesLedgerService.getDetail(principal, saleId));
  }

  @PostMapping("/{saleId}/mark-paid")
  @Operation(summary = "Mark credit/pending sale as paid (owner only)")
  public ApiResponse<Map<String, Object>> markPaid(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID saleId,
      @RequestBody(required = false) Map<String, Object> body) {
    return ApiResponse.ok(salesLedgerService.markPaid(principal, saleId, body));
  }
}
