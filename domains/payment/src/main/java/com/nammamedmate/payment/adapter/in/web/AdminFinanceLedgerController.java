package com.nammamedmate.payment.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.payment.application.LedgerFacadeService;
import com.nammamedmate.payment.application.LedgerFacadeService.PagedResult;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/finance/ledger")
@Tag(name = "Admin financial ledger")
public class AdminFinanceLedgerController {

  private final LedgerFacadeService ledger;

  public AdminFinanceLedgerController(LedgerFacadeService ledger) {
    this.ledger = ledger;
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission("finance:read")
  @Operation(summary = "Admin: browse financial ledger with KPI chips")
  public ApiResponse<Map<String, Object>> browse(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String order) {
    PagedResult result = ledger.browse(principal, type, from, to, page, limit, sort, order);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping(produces = "text/csv")
  @RequiresPermission("finance:read")
  @Operation(summary = "Admin: financial ledger CSV (Accept: text/csv)")
  public void browseCsv(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      HttpServletResponse response)
      throws Exception {
    byte[] csv = ledger.browseCsv(principal, type, from, to);
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType("text/csv; charset=UTF-8");
    response.setHeader(
        HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"financial-ledger.csv\"");
    response.getOutputStream().write(csv);
  }

  @GetMapping("/export")
  @RequiresPermission("finance:read")
  @Operation(summary = "Admin: export ledger CSV to downloadable URL")
  public ApiResponse<Map<String, Object>> export(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam String from,
      @RequestParam String to,
      @RequestParam(required = false) String type) {
    return ApiResponse.ok(ledger.export(principal, from, to, type));
  }
}
