package com.nammamedmate.inventory.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.inventory.application.port.out.DistributorStore;
import com.nammamedmate.inventory.application.port.out.DistributorSupplyItemStore;
import com.nammamedmate.inventory.application.port.out.PharmacyProductStore;
import com.nammamedmate.inventory.application.port.out.ProductBatchStore;
import com.nammamedmate.inventory.application.port.out.PurchaseGrnStore;
import com.nammamedmate.inventory.application.port.out.PurchaseGrnStore.GrnListRow;
import com.nammamedmate.inventory.application.port.out.PurchaseGrnStore.ItemWithProduct;
import com.nammamedmate.inventory.application.port.out.PurchaseGrnStore.KpiRow;
import com.nammamedmate.inventory.application.port.out.PurchaseGrnStore.ListFilter;
import com.nammamedmate.inventory.application.port.out.PurchaseGrnStore.ListResult;
import com.nammamedmate.inventory.domain.Distributor;
import com.nammamedmate.inventory.domain.DistributorFormats;
import com.nammamedmate.inventory.domain.GrnStatus;
import com.nammamedmate.inventory.domain.PharmacyProduct;
import com.nammamedmate.inventory.domain.ProductBatch;
import com.nammamedmate.inventory.domain.PurchaseGrn;
import com.nammamedmate.inventory.domain.PurchaseGrnItem;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PurchaseGrnService {

  private static final int WINDOW = 60;
  private static final int LIST_LIMIT = 60;
  private static final int CREATE_LIMIT = 30;
  private static final int ITEM_LIMIT = 60;
  private static final int MUTATE_LIMIT = 30;
  private static final int STOCK_LIMIT = 10;
  private static final int IMPORT_LIMIT = 5;
  private static final long MAX_CSV_BYTES = 5L * 1024 * 1024;
  private static final Set<Integer> GST_SLABS = Set.of(0, 5, 12, 18, 28);
  private static final Set<String> FORMS =
      Set.of("TABLET", "SYRUP", "CAPSULE", "DROPS", "INJECTION", "OTHER");
  private static final List<String> CSV_HEADERS =
      List.of(
          "product_name",
          "manufacturer",
          "batch_number",
          "expiry_date",
          "quantity",
          "free_quantity",
          "purchase_price",
          "mrp",
          "gst_pct");

  private final PurchaseGrnStore grnStore;
  private final DistributorStore distributorStore;
  private final DistributorSupplyItemStore supplyItemStore;
  private final PharmacyProductStore productStore;
  private final ProductBatchStore batchStore;
  private final RateLimiter rateLimiter;
  private final Clock clock;
  private final ObjectMapper objectMapper;

  public PurchaseGrnService(
      PurchaseGrnStore grnStore,
      DistributorStore distributorStore,
      DistributorSupplyItemStore supplyItemStore,
      PharmacyProductStore productStore,
      ProductBatchStore batchStore,
      RateLimiter rateLimiter,
      Clock clock,
      ObjectMapper objectMapper) {
    this.grnStore = grnStore;
    this.distributorStore = distributorStore;
    this.supplyItemStore = supplyItemStore;
    this.productStore = productStore;
    this.batchStore = batchStore;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.objectMapper = objectMapper;
  }

  public record ListPage(Map<String, Object> data, PaginationMeta meta) {
    public ListPage {
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  @Transactional(readOnly = true)
  public ListPage list(
      MedmatePrincipal principal,
      String status,
      UUID distributorId,
      LocalDate fromDate,
      LocalDate toDate,
      String q,
      Integer page,
      Integer limit) {
    requirePharmacyReader(principal);
    rateLimit("pharmacy:purchases:list:" + principal.pharmacyId(), LIST_LIMIT);

    int p = page == null || page < 1 ? 1 : page;
    int lim = limit == null ? 20 : Math.min(Math.max(limit, 1), 100);
    GrnStatus statusFilter = parseStatusOptional(status);

    ListResult result =
        grnStore.list(
            new ListFilter(
                principal.pharmacyId(), statusFilter, distributorId, fromDate, toDate, q, p, lim));

    LocalDate today = today();
    YearMonth ym = YearMonth.from(today);
    KpiRow kpi = grnStore.kpi(principal.pharmacyId(), ym.atDay(1), ym.plusMonths(1).atDay(1));

    List<Map<String, Object>> grns = new ArrayList<>(result.rows().size());
    for (GrnListRow row : result.rows()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("grn_id", row.grnId().toString());
      m.put("distributor_name", row.distributorName());
      m.put("invoice_number", row.invoiceNumber());
      m.put("invoice_date", row.invoiceDate().toString());
      m.put("line_count", row.lineCount());
      m.put("taxable_amount", paiseToRupees(row.taxableAmountPaise()));
      m.put("gst_amount", paiseToRupees(row.gstAmountPaise()));
      m.put("total", paiseToRupees(row.totalPaise()));
      m.put("status", row.status().name());
      m.put("created_at", row.createdAt().toString());
      grns.add(m);
    }

    Map<String, Object> kpiMap = new LinkedHashMap<>();
    kpiMap.put("purchases_this_month", kpi.purchasesThisMonth());
    kpiMap.put("input_gst_credit_this_month", paiseToRupees(kpi.inputGstCreditThisMonthPaise()));
    kpiMap.put("total_grns", kpi.totalGrns());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("kpi", kpiMap);
    data.put("grns", grns);
    return new ListPage(data, PaginationMeta.of(p, lim, result.total()));
  }

  @Transactional
  public Map<String, Object> create(
      MedmatePrincipal principal, UUID distributorId, String invoiceNumber, LocalDate invoiceDate) {
    requirePharmacyReader(principal);
    rateLimit("pharmacy:purchases:create:" + principal.pharmacyId(), CREATE_LIMIT);

    Distributor distributor = requireUsableDistributor(principal.pharmacyId(), distributorId);
    validateInvoiceHeader(principal.pharmacyId(), distributorId, invoiceNumber, invoiceDate);

    Instant now = clock.instant();
    PurchaseGrn grn =
        new PurchaseGrn(
            UUID.randomUUID(),
            principal.pharmacyId(),
            distributorId,
            invoiceNumber.trim(),
            invoiceDate,
            GrnStatus.DRAFT,
            null,
            null,
            principal.subject(),
            null,
            now,
            now,
            null);
    grnStore.insert(grn);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("grn_id", grn.id().toString());
    data.put("distributor_id", distributor.id().toString());
    data.put("distributor_name", distributor.firmName());
    data.put("invoice_number", grn.invoiceNumber());
    data.put("invoice_date", grn.invoiceDate().toString());
    data.put("status", GrnStatus.DRAFT.name());
    data.put("line_count", 0);
    data.put("created_at", now.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> addItem(
      MedmatePrincipal principal,
      UUID grnId,
      String productSearchQuery,
      UUID productId,
      Boolean createNewProduct,
      String newProductName,
      String newProductManufacturer,
      Integer newProductPackSize,
      String newProductForm,
      String batchNumber,
      LocalDate expiryDate,
      LocalDate manufacturedDate,
      Integer quantity,
      Integer freeQuantity,
      BigDecimal purchasePricePerUnit,
      BigDecimal mrpPerUnit,
      BigDecimal gstPct) {
    requirePharmacyReader(principal);
    rateLimit("pharmacy:purchases:items:add:" + principal.pharmacyId(), ITEM_LIMIT);

    PurchaseGrn grn = requireEditableGrn(principal.pharmacyId(), grnId);
    LocalDate today = today();
    validateLineBasics(batchNumber, expiryDate, quantity, freeQuantity, today);
    int gst = validateGst(gstPct);
    long purchasePaise = rupeesToPaise(purchasePricePerUnit, "purchase_price_per_unit");
    long mrpPaise = rupeesToPaise(mrpPerUnit, "mrp_per_unit");
    int free = freeQuantity == null ? 0 : freeQuantity;

    boolean createNew = Boolean.TRUE.equals(createNewProduct);
    PharmacyProduct product;
    boolean isNew = false;
    if (createNew) {
      product =
          createProduct(
              principal.pharmacyId(),
              newProductName,
              newProductManufacturer,
              newProductPackSize,
              newProductForm,
              gst,
              mrpPaise);
      isNew = true;
    } else if (productId != null) {
      product =
          productStore
              .findById(principal.pharmacyId(), productId)
              .orElseThrow(() -> new AppException("PRODUCT_NOT_FOUND", "Product not found", 404));
    } else if (productSearchQuery != null && !productSearchQuery.isBlank()) {
      List<PharmacyProduct> matches =
          productStore.searchByName(principal.pharmacyId(), productSearchQuery.trim(), 5);
      if (matches.isEmpty()) {
        throw new AppException(
            "PRODUCT_NOT_FOUND",
            "No product matched search; set create_new_product=true to create",
            404);
      }
      product = matches.getFirst();
    } else {
      throw new AppException(
          "VALIDATION_ERROR",
          "product_id, product_search_query, or create_new_product required",
          400);
    }

    Instant now = clock.instant();
    long taxable = PurchaseGrnItem.taxablePaise(quantity, purchasePaise);
    long gstAmt = PurchaseGrnItem.gstPaise(taxable, gst);
    long lineTotal = PurchaseGrnItem.lineTotalPaise(taxable, gstAmt);
    PurchaseGrnItem item =
        new PurchaseGrnItem(
            UUID.randomUUID(),
            grn.id(),
            principal.pharmacyId(),
            product.id(),
            batchNumber.trim(),
            expiryDate,
            manufacturedDate,
            quantity,
            free,
            purchasePaise,
            mrpPaise,
            gst,
            taxable,
            gstAmt,
            lineTotal,
            isNew,
            now,
            now);
    grnStore.insertItem(item);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("item_id", item.id().toString());
    data.put("grn_id", grn.id().toString());
    data.put("product_id", product.id().toString());
    data.put("product_name", product.name());
    data.put("is_new_product", isNew);
    data.put("batch_number", item.batchNumber());
    data.put("expiry_date", item.expiryDate().toString());
    data.put("quantity", item.quantity());
    data.put("free_quantity", item.freeQuantity());
    data.put("quantity_total", item.quantityTotal());
    data.put("purchase_price_per_unit", paiseToRupees(purchasePaise));
    data.put("mrp_per_unit", paiseToRupees(mrpPaise));
    data.put("gst_pct", gst);
    data.put("taxable_amount", paiseToRupees(taxable));
    data.put("gst_amount", paiseToRupees(gstAmt));
    data.put("line_total", paiseToRupees(lineTotal));
    return data;
  }

  @Transactional
  public Map<String, Object> patchItem(
      MedmatePrincipal principal,
      UUID grnId,
      UUID itemId,
      Integer quantity,
      Integer freeQuantity,
      BigDecimal purchasePricePerUnit,
      BigDecimal mrpPerUnit,
      LocalDate expiryDate,
      BigDecimal gstPct) {
    requirePharmacyReader(principal);
    rateLimit("pharmacy:purchases:items:patch:" + principal.pharmacyId(), MUTATE_LIMIT);

    requireEditableGrn(principal.pharmacyId(), grnId);
    PurchaseGrnItem cur =
        grnStore
            .findItem(principal.pharmacyId(), grnId, itemId)
            .orElseThrow(() -> new AppException("ITEM_NOT_FOUND", "Item not found", 404));

    int qty = quantity == null ? cur.quantity() : quantity;
    int free = freeQuantity == null ? cur.freeQuantity() : freeQuantity;
    if (qty <= 0) {
      throw new AppException("VALIDATION_ERROR", "quantity must be > 0", 400);
    }
    if (free < 0) {
      throw new AppException("VALIDATION_ERROR", "free_quantity must be >= 0", 400);
    }
    LocalDate expiry = expiryDate == null ? cur.expiryDate() : expiryDate;
    if (expiry.isBefore(today())) {
      throw new AppException("EXPIRY_DATE_IN_PAST", "expiry_date must be today or later", 400);
    }
    long purchasePaise =
        purchasePricePerUnit == null
            ? cur.purchasePricePaise()
            : rupeesToPaise(purchasePricePerUnit, "purchase_price_per_unit");
    long mrpPaise = mrpPerUnit == null ? cur.mrpPaise() : rupeesToPaise(mrpPerUnit, "mrp_per_unit");
    int gst = gstPct == null ? cur.gstPct() : validateGst(gstPct);

    Instant now = clock.instant();
    long taxable = PurchaseGrnItem.taxablePaise(qty, purchasePaise);
    long gstAmt = PurchaseGrnItem.gstPaise(taxable, gst);
    long lineTotal = PurchaseGrnItem.lineTotalPaise(taxable, gstAmt);
    PurchaseGrnItem updated =
        new PurchaseGrnItem(
            cur.id(),
            cur.grnId(),
            cur.pharmacyId(),
            cur.productId(),
            cur.batchNumber(),
            expiry,
            cur.manufacturedDate(),
            qty,
            free,
            purchasePaise,
            mrpPaise,
            gst,
            taxable,
            gstAmt,
            lineTotal,
            cur.newProduct(),
            cur.createdAt(),
            now);
    grnStore.updateItem(updated);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("item_id", updated.id().toString());
    data.put("quantity", updated.quantity());
    data.put("taxable_amount", paiseToRupees(taxable));
    data.put("gst_amount", paiseToRupees(gstAmt));
    data.put("line_total", paiseToRupees(lineTotal));
    data.put("updated_at", now.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> deleteItem(MedmatePrincipal principal, UUID grnId, UUID itemId) {
    requirePharmacyReader(principal);
    rateLimit("pharmacy:purchases:items:delete:" + principal.pharmacyId(), MUTATE_LIMIT);

    requireEditableGrn(principal.pharmacyId(), grnId);
    if (!grnStore.deleteItem(principal.pharmacyId(), grnId, itemId)) {
      throw new AppException("ITEM_NOT_FOUND", "Item not found", 404);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("item_id", itemId.toString());
    data.put("deleted", true);
    return data;
  }

  @Transactional
  public Map<String, Object> saveAndStock(MedmatePrincipal principal, UUID grnId) {
    requirePharmacyReader(principal);
    if (principal.role() != AuthRole.PHARMACY_OWNER) {
      throw new AppException("STAFF_CANNOT_STOCK", "Only pharmacy_owner may finalize GRN", 403);
    }
    rateLimit("pharmacy:purchases:stock:" + principal.pharmacyId(), STOCK_LIMIT);

    PurchaseGrn grn =
        grnStore
            .findById(principal.pharmacyId(), grnId)
            .orElseThrow(() -> new AppException("GRN_NOT_FOUND", "GRN not found", 404));
    if (grn.status() == GrnStatus.STOCKED) {
      throw new AppException("GRN_ALREADY_STOCKED", "GRN already finalized", 400);
    }

    List<ItemWithProduct> items = grnStore.listItems(principal.pharmacyId(), grnId);
    if (items.isEmpty()) {
      throw new AppException("GRN_EMPTY", "GRN has no line items", 400);
    }

    Instant now = clock.instant();
    int newProducts = 0;
    int batchesCreated = 0;
    int batchesTopped = 0;
    int totalUnits = 0;
    long totalCostPaise = 0;
    long totalGstPaise = 0;
    List<Map<String, Object>> summary = new ArrayList<>();

    for (ItemWithProduct row : items) {
      PurchaseGrnItem item = row.item();
      if (item.newProduct()) {
        newProducts++;
      }
      int units = item.quantityTotal();
      totalUnits += units;
      totalCostPaise = Math.addExact(totalCostPaise, item.lineTotalPaise());
      totalGstPaise = Math.addExact(totalGstPaise, item.gstAmountPaise());

      var existing =
          batchStore.findByBatchNumber(
              principal.pharmacyId(), item.productId(), item.batchNumber());
      if (existing.isPresent()) {
        ProductBatch cur = existing.get();
        int received = cur.quantityReceived() + units;
        int current = cur.quantityCurrent() + units;
        batchStore.topUpFromGrn(
            cur.id(),
            received,
            current,
            item.purchasePricePaise(),
            item.mrpPaise(),
            item.id(),
            now);
        batchStore.insertStockMovement(
            UUID.randomUUID(),
            principal.pharmacyId(),
            item.productId(),
            cur.id(),
            "RECEIPT",
            units,
            "GRN_TOP_UP",
            principal.subject(),
            now);
        batchesTopped++;
      } else {
        ProductBatch created =
            new ProductBatch(
                UUID.randomUUID(),
                item.productId(),
                principal.pharmacyId(),
                item.batchNumber(),
                item.expiryDate(),
                item.manufacturedDate(),
                units,
                units,
                item.purchasePricePaise(),
                item.mrpPaise(),
                true,
                null,
                null,
                item.id(),
                now,
                now);
        batchStore.insert(created);
        batchStore.insertStockMovement(
            UUID.randomUUID(),
            principal.pharmacyId(),
            item.productId(),
            created.id(),
            "RECEIPT",
            units,
            item.freeQuantity() > 0 ? "GRN_RECEIPT_WITH_FREE" : "GRN_RECEIPT",
            principal.subject(),
            now);
        batchesCreated++;
      }

      productStore.updateMrp(principal.pharmacyId(), item.productId(), item.mrpPaise(), now);
      batchStore.refreshProductDenorm(principal.pharmacyId(), item.productId(), now);
      supplyItemStore.upsertFromGrn(
          principal.pharmacyId(),
          grn.distributorId(),
          item.productId(),
          item.purchasePricePaise(),
          DistributorFormats.schemeDescription(item.freeQuantity(), item.quantity()),
          now);
      PharmacyProduct refreshed =
          productStore
              .findById(principal.pharmacyId(), item.productId())
              .orElseThrow(() -> new AppException("PRODUCT_NOT_FOUND", "Product not found", 404));

      Map<String, Object> line = new LinkedHashMap<>();
      line.put("product_id", item.productId().toString());
      line.put("product_name", row.productName());
      line.put("units_added", units);
      line.put("new_total_stock", refreshed.totalStockUnits());
      summary.add(line);
    }

    // DRAFT → SAVED → STOCKED in one call (header locked conceptually via STOCKED)
    grnStore.updateStatus(grn.id(), GrnStatus.STOCKED, now, principal.subject(), now);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("grn_id", grn.id().toString());
    data.put("status", GrnStatus.STOCKED.name());
    data.put("stocked_at", now.toString());
    data.put("line_count", items.size());
    data.put("new_products_created", newProducts);
    data.put("batches_created", batchesCreated);
    data.put("batches_topped_up", batchesTopped);
    data.put("total_units_added", totalUnits);
    data.put("total_value_at_cost", paiseToRupees(totalCostPaise));
    data.put("total_input_gst_credit", paiseToRupees(totalGstPaise));
    data.put("updated_stock_summary", summary);
    return data;
  }

  @Transactional
  public Map<String, Object> importCsv(
      MedmatePrincipal principal,
      MultipartFile csvFile,
      UUID distributorId,
      String invoiceNumber,
      LocalDate invoiceDate) {
    requirePharmacyReader(principal);
    rateLimit("pharmacy:purchases:import:" + principal.pharmacyId(), IMPORT_LIMIT);

    if (csvFile == null || csvFile.isEmpty()) {
      throw new AppException("INVALID_CSV_FORMAT", "csv_file is required", 400);
    }
    if (csvFile.getSize() > MAX_CSV_BYTES) {
      throw new AppException("FILE_TOO_LARGE", "CSV exceeds 5MB", 400);
    }

    Distributor distributor = requireUsableDistributor(principal.pharmacyId(), distributorId);
    validateInvoiceHeader(principal.pharmacyId(), distributorId, invoiceNumber, invoiceDate);

    List<String[]> rows;
    try {
      rows = parseCsv(csvFile);
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      throw new AppException("INVALID_CSV_FORMAT", "Unable to parse CSV", 400);
    }

    Instant now = clock.instant();
    PurchaseGrn grn =
        new PurchaseGrn(
            UUID.randomUUID(),
            principal.pharmacyId(),
            distributorId,
            invoiceNumber.trim(),
            invoiceDate,
            GrnStatus.DRAFT,
            null,
            null,
            principal.subject(),
            null,
            now,
            now,
            null);
    grnStore.insert(grn);

    List<Map<String, Object>> previewItems = new ArrayList<>();
    List<Map<String, Object>> unmatchedItems = new ArrayList<>();
    List<Map<String, Object>> unmatchedPersist = new ArrayList<>();
    int matched = 0;

    for (int i = 0; i < rows.size(); i++) {
      String[] cols = rows.get(i);
      int rowNumber = i + 2; // header is row 1
      Map<String, String> raw = rowMap(cols);
      String name = raw.get("product_name");
      String manufacturer = raw.getOrDefault("manufacturer", "");
      var productOpt =
          productStore.findByNameAndManufacturer(principal.pharmacyId(), name, manufacturer);
      if (productOpt.isEmpty()) {
        Map<String, Object> um = new LinkedHashMap<>();
        um.put("row_number", rowNumber);
        Map<String, Object> rawData = new LinkedHashMap<>();
        rawData.put("product_name", name);
        rawData.put("manufacturer", manufacturer);
        um.put("raw_data", rawData);
        um.put("suggested_action", "CREATE_NEW");
        um.put("csv_row", raw);
        unmatchedItems.add(
            Map.of(
                "row_number", rowNumber,
                "raw_data", rawData,
                "suggested_action", "CREATE_NEW"));
        unmatchedPersist.add(um);
        continue;
      }

      LocalDate expiry = LocalDate.parse(raw.get("expiry_date"));
      int qty = Integer.parseInt(raw.get("quantity"));
      int free = parseFreeQty(raw.getOrDefault("free_quantity", ""));
      BigDecimal purchase = new BigDecimal(raw.get("purchase_price"));
      BigDecimal mrp = new BigDecimal(raw.get("mrp"));
      BigDecimal gstBd = new BigDecimal(raw.get("gst_pct"));
      int gst = validateGst(gstBd);
      long purchasePaise = rupeesToPaise(purchase, "purchase_price");
      long mrpPaise = rupeesToPaise(mrp, "mrp");
      if (expiry.isBefore(today())) {
        throw new AppException(
            "EXPIRY_DATE_IN_PAST", "Row " + rowNumber + " expiry_date in the past", 400);
      }
      if (qty <= 0) {
        throw new AppException(
            "VALIDATION_ERROR", "Row " + rowNumber + " quantity must be > 0", 400);
      }

      long taxable = PurchaseGrnItem.taxablePaise(qty, purchasePaise);
      long gstAmt = PurchaseGrnItem.gstPaise(taxable, gst);
      long lineTotal = PurchaseGrnItem.lineTotalPaise(taxable, gstAmt);
      PharmacyProduct product = productOpt.get();
      PurchaseGrnItem item =
          new PurchaseGrnItem(
              UUID.randomUUID(),
              grn.id(),
              principal.pharmacyId(),
              product.id(),
              raw.get("batch_number").trim(),
              expiry,
              null,
              qty,
              free,
              purchasePaise,
              mrpPaise,
              gst,
              taxable,
              gstAmt,
              lineTotal,
              false,
              now,
              now);
      grnStore.insertItem(item);
      matched++;

      Map<String, Object> preview = new LinkedHashMap<>();
      preview.put("item_id", item.id().toString());
      preview.put("product_id", product.id().toString());
      preview.put("product_name", product.name());
      preview.put("batch_number", item.batchNumber());
      preview.put("quantity", qty);
      preview.put("free_quantity", free);
      previewItems.add(preview);
    }

    String unmatchedJson;
    try {
      unmatchedJson = objectMapper.writeValueAsString(unmatchedPersist);
    } catch (Exception e) {
      throw new AppException("INVALID_CSV_FORMAT", "Unable to persist unmatched rows", 400);
    }
    grnStore.updateImportUnmatched(grn.id(), unmatchedJson, now);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("grn_id", grn.id().toString());
    data.put("distributor_name", distributor.firmName());
    data.put("total_rows", rows.size());
    data.put("matched_rows", matched);
    data.put("unmatched_rows", unmatchedItems.size());
    data.put("status", GrnStatus.DRAFT.name());
    data.put("preview_items", previewItems);
    data.put("unmatched_items", unmatchedItems);
    return data;
  }

  @Transactional
  public Map<String, Object> confirmImport(MedmatePrincipal principal, UUID grnId) {
    requirePharmacyReader(principal);
    rateLimit("pharmacy:purchases:confirm-import:" + principal.pharmacyId(), IMPORT_LIMIT);

    PurchaseGrn grn = requireEditableGrn(principal.pharmacyId(), grnId);
    if (grn.importUnmatchedJson() == null || grn.importUnmatchedJson().isBlank()) {
      Map<String, Object> empty = new LinkedHashMap<>();
      empty.put("grn_id", grn.id().toString());
      empty.put("items_created", 0);
      empty.put("status", grn.status().name());
      return empty;
    }

    List<Map<String, Object>> pending;
    try {
      pending =
          objectMapper.readValue(
              grn.importUnmatchedJson(), new TypeReference<List<Map<String, Object>>>() {});
    } catch (Exception e) {
      throw new AppException("INVALID_CSV_FORMAT", "Corrupt import preview data", 400);
    }

    Instant now = clock.instant();
    int created = 0;
    for (Map<String, Object> um : pending) {
      @SuppressWarnings("unchecked")
      Map<String, Object> csvRaw = (Map<String, Object>) um.get("csv_row");
      if (csvRaw == null) {
        continue;
      }
      Map<String, String> csvRow = new LinkedHashMap<>();
      for (Map.Entry<String, Object> e : csvRaw.entrySet()) {
        csvRow.put(e.getKey(), e.getValue() == null ? "" : e.getValue().toString());
      }
      String name = csvRow.get("product_name");
      String manufacturer = csvRow.getOrDefault("manufacturer", "");
      int gst = validateGst(new BigDecimal(csvRow.get("gst_pct")));
      long mrpPaise = rupeesToPaise(new BigDecimal(csvRow.get("mrp")), "mrp");
      PharmacyProduct product =
          createProduct(principal.pharmacyId(), name, manufacturer, 1, "OTHER", gst, mrpPaise);

      int qty = Integer.parseInt(csvRow.get("quantity"));
      int free = parseFreeQty(csvRow.getOrDefault("free_quantity", ""));
      long purchasePaise =
          rupeesToPaise(new BigDecimal(csvRow.get("purchase_price")), "purchase_price");
      LocalDate expiry = LocalDate.parse(csvRow.get("expiry_date"));
      long taxable = PurchaseGrnItem.taxablePaise(qty, purchasePaise);
      long gstAmt = PurchaseGrnItem.gstPaise(taxable, gst);
      long lineTotal = PurchaseGrnItem.lineTotalPaise(taxable, gstAmt);

      PurchaseGrnItem item =
          new PurchaseGrnItem(
              UUID.randomUUID(),
              grn.id(),
              principal.pharmacyId(),
              product.id(),
              csvRow.get("batch_number").trim(),
              expiry,
              null,
              qty,
              free,
              purchasePaise,
              mrpPaise,
              gst,
              taxable,
              gstAmt,
              lineTotal,
              true,
              now,
              now);
      grnStore.insertItem(item);
      created++;
    }

    grnStore.updateImportUnmatched(grn.id(), null, now);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("grn_id", grn.id().toString());
    data.put("items_created", created);
    data.put("status", GrnStatus.DRAFT.name());
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> get(MedmatePrincipal principal, UUID grnId) {
    requirePharmacyReader(principal);
    rateLimit("pharmacy:purchases:get:" + principal.pharmacyId(), LIST_LIMIT);

    PurchaseGrn grn =
        grnStore
            .findById(principal.pharmacyId(), grnId)
            .orElseThrow(() -> new AppException("GRN_NOT_FOUND", "GRN not found", 404));

    String firm = grnStore.distributorFirmName(principal.pharmacyId(), grn.distributorId());
    List<ItemWithProduct> items = grnStore.listItems(principal.pharmacyId(), grnId);

    long taxableSum = 0;
    long gstSum = 0;
    long totalSum = 0;
    List<Map<String, Object>> itemMaps = new ArrayList<>(items.size());
    for (ItemWithProduct row : items) {
      PurchaseGrnItem item = row.item();
      taxableSum = Math.addExact(taxableSum, item.taxableAmountPaise());
      gstSum = Math.addExact(gstSum, item.gstAmountPaise());
      totalSum = Math.addExact(totalSum, item.lineTotalPaise());
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("item_id", item.id().toString());
      m.put("product_id", item.productId().toString());
      m.put("product_name", row.productName());
      m.put("batch_number", item.batchNumber());
      m.put("expiry_date", item.expiryDate().toString());
      m.put("quantity", item.quantity());
      m.put("free_quantity", item.freeQuantity());
      m.put("purchase_price_per_unit", paiseToRupees(item.purchasePricePaise()));
      m.put("mrp_per_unit", paiseToRupees(item.mrpPaise()));
      m.put("gst_pct", item.gstPct());
      m.put("taxable_amount", paiseToRupees(item.taxableAmountPaise()));
      m.put("gst_amount", paiseToRupees(item.gstAmountPaise()));
      m.put("line_total", paiseToRupees(item.lineTotalPaise()));
      itemMaps.add(m);
    }

    Map<String, Object> distributor = new LinkedHashMap<>();
    distributor.put("id", grn.distributorId().toString());
    distributor.put("firm_name", firm);

    Map<String, Object> totals = new LinkedHashMap<>();
    totals.put("taxable_amount", paiseToRupees(taxableSum));
    totals.put("gst_amount", paiseToRupees(gstSum));
    totals.put("grand_total", paiseToRupees(totalSum));
    totals.put("input_gst_credit", paiseToRupees(gstSum));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("grn_id", grn.id().toString());
    data.put("distributor", distributor);
    data.put("invoice_number", grn.invoiceNumber());
    data.put("invoice_date", grn.invoiceDate().toString());
    data.put("status", grn.status().name());
    data.put("items", itemMaps);
    data.put("totals", totals);
    return data;
  }

  private PharmacyProduct createProduct(
      UUID pharmacyId,
      String name,
      String manufacturer,
      Integer packSize,
      String form,
      int gstPct,
      long mrpPaise) {
    if (name == null || name.isBlank() || name.length() > 200) {
      throw new AppException("VALIDATION_ERROR", "new_product_name required (max 200)", 400);
    }
    if (packSize == null || packSize <= 0) {
      throw new AppException("VALIDATION_ERROR", "new_product_pack_size must be > 0", 400);
    }
    if (form == null || form.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "new_product_form is required", 400);
    }
    String formKey = form.trim().toUpperCase(Locale.ROOT);
    if (!FORMS.contains(formKey)) {
      throw new AppException("VALIDATION_ERROR", "Invalid new_product_form", 400);
    }
    Instant now = clock.instant();
    String packUnit =
        switch (formKey) {
          case "SYRUP", "DROPS" -> "ml";
          case "INJECTION" -> "vial";
          default -> "units";
        };
    PharmacyProduct product =
        new PharmacyProduct(
            UUID.randomUUID(),
            pharmacyId,
            null,
            name.trim(),
            null,
            manufacturer == null || manufacturer.isBlank() ? null : manufacturer.trim(),
            packSize,
            packUnit,
            null,
            null,
            formKey,
            "OTC",
            null,
            BigDecimal.valueOf(gstPct),
            mrpPaise,
            false,
            false,
            false,
            0,
            List.of(),
            0,
            0,
            null,
            0,
            null,
            null,
            now,
            now);
    return productStore.insert(product);
  }

  private List<String[]> parseCsv(MultipartFile file) throws Exception {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
      String headerLine = reader.readLine();
      if (headerLine == null) {
        throw new AppException("INVALID_CSV_FORMAT", "CSV is empty", 400);
      }
      String[] headers = splitCsvLine(headerLine);
      Map<String, Integer> idx = new LinkedHashMap<>();
      for (int i = 0; i < headers.length; i++) {
        idx.put(headers[i].trim().toLowerCase(Locale.ROOT), i);
      }
      for (String required : CSV_HEADERS) {
        if (!idx.containsKey(required)) {
          throw new AppException(
              "INVALID_CSV_FORMAT", "CSV missing required column: " + required, 400);
        }
      }
      List<String[]> rows = new ArrayList<>();
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        String[] cols = splitCsvLine(line);
        String[] ordered = new String[CSV_HEADERS.size()];
        for (int i = 0; i < CSV_HEADERS.size(); i++) {
          int colIdx = idx.get(CSV_HEADERS.get(i));
          ordered[i] = colIdx >= cols.length ? "" : cols[colIdx].trim();
        }
        rows.add(ordered);
      }
      return rows;
    }
  }

  private static String[] splitCsvLine(String line) {
    List<String> parts = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean inQuotes = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '"') {
        inQuotes = !inQuotes;
      } else if (c == ',') {
        if (inQuotes) {
          cur.append(c);
        } else {
          parts.add(cur.toString());
          cur.setLength(0);
        }
      } else {
        cur.append(c);
      }
    }
    parts.add(cur.toString());
    return parts.toArray(String[]::new);
  }

  private static int parseFreeQty(String raw) {
    if (raw.isBlank()) {
      return 0;
    }
    return Integer.parseInt(raw);
  }

  private static Map<String, String> rowMap(String[] cols) {
    Map<String, String> m = new LinkedHashMap<>();
    for (int i = 0; i < CSV_HEADERS.size(); i++) {
      // ordered rows from parseCsv are always CSV_HEADERS-sized
      m.put(CSV_HEADERS.get(i), cols[i]);
    }
    return m;
  }

  private Distributor requireUsableDistributor(UUID pharmacyId, UUID distributorId) {
    if (distributorId == null) {
      throw new AppException("VALIDATION_ERROR", "distributor_id is required", 400);
    }
    Distributor d =
        distributorStore
            .findByIdIncludingDeleted(pharmacyId, distributorId)
            .orElseThrow(
                () -> new AppException("DISTRIBUTOR_NOT_FOUND", "Distributor not found", 404));
    if (d.deletedAt() != null) {
      throw new AppException("DISTRIBUTOR_NOT_FOUND", "Distributor not found", 404);
    }
    if (!d.active()) {
      throw new AppException("DISTRIBUTOR_INACTIVE", "Distributor is inactive", 400);
    }
    return d;
  }

  private void validateInvoiceHeader(
      UUID pharmacyId, UUID distributorId, String invoiceNumber, LocalDate invoiceDate) {
    if (invoiceNumber == null || invoiceNumber.isBlank() || invoiceNumber.length() > 100) {
      throw new AppException("VALIDATION_ERROR", "invoice_number required (max 100)", 400);
    }
    if (invoiceDate == null) {
      throw new AppException("VALIDATION_ERROR", "invoice_date is required", 400);
    }
    if (invoiceDate.isAfter(today())) {
      throw new AppException("FUTURE_INVOICE_DATE", "invoice_date cannot be in the future", 400);
    }
    if (grnStore.invoiceExists(pharmacyId, distributorId, invoiceNumber.trim())) {
      throw new AppException(
          "DUPLICATE_INVOICE_NUMBER", "Invoice already exists for this distributor", 400);
    }
  }

  private PurchaseGrn requireEditableGrn(UUID pharmacyId, UUID grnId) {
    PurchaseGrn grn =
        grnStore
            .findById(pharmacyId, grnId)
            .orElseThrow(() -> new AppException("GRN_NOT_FOUND", "GRN not found", 404));
    if (!grn.status().editable()) {
      throw new AppException("GRN_ALREADY_STOCKED", "GRN is finalized", 400);
    }
    return grn;
  }

  private void validateLineBasics(
      String batchNumber,
      LocalDate expiryDate,
      Integer quantity,
      Integer freeQuantity,
      LocalDate today) {
    if (batchNumber == null || batchNumber.isBlank() || batchNumber.length() > 50) {
      throw new AppException("VALIDATION_ERROR", "batch_number required (max 50)", 400);
    }
    if (expiryDate == null || expiryDate.isBefore(today)) {
      throw new AppException("EXPIRY_DATE_IN_PAST", "expiry_date must be today or later", 400);
    }
    if (quantity == null || quantity <= 0) {
      throw new AppException("VALIDATION_ERROR", "quantity must be > 0", 400);
    }
    if (freeQuantity != null && freeQuantity < 0) {
      throw new AppException("VALIDATION_ERROR", "free_quantity must be >= 0", 400);
    }
  }

  private static int validateGst(BigDecimal gstPct) {
    if (gstPct == null) {
      throw new AppException("INVALID_GST_PCT", "gst_pct is required", 400);
    }
    int gst;
    try {
      gst = gstPct.stripTrailingZeros().intValueExact();
    } catch (ArithmeticException e) {
      throw new AppException("INVALID_GST_PCT", "gst_pct must be a whole slab", 400);
    }
    if (!GST_SLABS.contains(gst)) {
      throw new AppException("INVALID_GST_PCT", "gst_pct not in allowed slabs", 400);
    }
    return gst;
  }

  private static GrnStatus parseStatusOptional(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    try {
      return GrnStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new AppException("VALIDATION_ERROR", "Invalid status filter", 400);
    }
  }

  private LocalDate today() {
    return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
  }

  static BigDecimal paiseToRupees(long paise) {
    return BigDecimal.valueOf(paise).movePointLeft(2);
  }

  static long rupeesToPaise(BigDecimal value, String field) {
    if (value == null) {
      throw new AppException("VALIDATION_ERROR", field + " is required", 400);
    }
    if (value.scale() > 2) {
      throw new AppException("VALIDATION_ERROR", field + " may have at most 2 decimal places", 400);
    }
    if (value.compareTo(BigDecimal.ZERO) <= 0) {
      throw new AppException("VALIDATION_ERROR", field + " must be > 0", 400);
    }
    return value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
  }

  private void rateLimit(String key, int limit) {
    if (!rateLimiter.tryAcquire(key, limit, WINDOW)) {
      throw new AppException("RATE_LIMIT_EXCEEDED", "Too many requests", 429);
    }
  }

  private static void requirePharmacyReader(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.PHARMACY_OWNER && role != AuthRole.PHARMACY_STAFF) {
      throw new AppException("FORBIDDEN", "Pharmacy role required", 403);
    }
    if (principal.pharmacyId() == null) {
      throw new AppException("UNAUTHORIZED", "Pharmacy context missing", 401);
    }
  }
}
