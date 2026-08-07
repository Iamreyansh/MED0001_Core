package com.nammamedmate.catalogue.adapter.in.web;

import com.nammamedmate.catalogue.application.MedicineSearchService;
import com.nammamedmate.catalogue.application.MedicineSearchService.Envelope;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pharmacy/catalogue")
@Tag(name = "Pharmacy catalogue search")
public class PharmacyCatalogueSearchController {

  private final MedicineSearchService service;

  public PharmacyCatalogueSearchController(MedicineSearchService service) {
    this.service = service;
  }

  @GetMapping("/search")
  @Operation(summary = "Pharmacy-scoped master (+ empty CUSTOM until EPIC-006) search")
  public Map<String, Object> search(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam("q") String q,
      @RequestParam(value = "source", required = false) String source,
      @RequestParam(value = "in_stock_only", required = false) Boolean inStockOnly,
      @RequestParam(value = "show_oos", defaultValue = "false") boolean showOos,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "limit", required = false) Integer limit) {
    // show_oos=true means include OOS (inverse of in_stock_only)
    Boolean stockOnly = inStockOnly;
    if (showOos) {
      stockOnly = false;
    }
    Envelope env = service.pharmacySearch(principal, q, source, stockOnly, page, limit);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("success", true);
    body.put("data", env.data());
    body.put("meta", env.meta() == null ? Map.of() : env.meta());
    return body;
  }
}
