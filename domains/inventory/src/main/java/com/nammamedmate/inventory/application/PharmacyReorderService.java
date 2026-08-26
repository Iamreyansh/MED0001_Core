package com.nammamedmate.inventory.application;

import com.nammamedmate.inventory.adapter.out.export.SimplePdfExporter;
import com.nammamedmate.inventory.application.port.out.DistributorStore;
import com.nammamedmate.inventory.application.port.out.InventoryPlanPort;
import com.nammamedmate.inventory.application.port.out.PharmacyProductStore;
import com.nammamedmate.inventory.application.port.out.PurchaseGrnStore;
import com.nammamedmate.inventory.application.port.out.PurchaseOrderStore;
import com.nammamedmate.inventory.application.port.out.PurchaseOrderStore.ItemWithProduct;
import com.nammamedmate.inventory.application.port.out.PurchaseOrderStore.ListFilter;
import com.nammamedmate.inventory.application.port.out.PurchaseOrderStore.ListResult;
import com.nammamedmate.inventory.application.port.out.PurchaseOrderStore.PoListRow;
import com.nammamedmate.inventory.application.port.out.ReorderSuggestionStore;
import com.nammamedmate.inventory.application.port.out.ReorderSuggestionStore.LowStockProduct;
import com.nammamedmate.inventory.application.port.out.ReorderSuggestionStore.SuggestionRow;
import com.nammamedmate.inventory.application.port.out.ReorderSuggestionStore.SupplyOffer;
import com.nammamedmate.inventory.domain.Distributor;
import com.nammamedmate.inventory.domain.DistributorFormats;
import com.nammamedmate.inventory.domain.GrnStatus;
import com.nammamedmate.inventory.domain.PharmacyProduct;
import com.nammamedmate.inventory.domain.PoSentChannel;
import com.nammamedmate.inventory.domain.PurchaseGrn;
import com.nammamedmate.inventory.domain.PurchaseGrnItem;
import com.nammamedmate.inventory.domain.PurchaseOrder;
import com.nammamedmate.inventory.domain.PurchaseOrderItem;
import com.nammamedmate.inventory.domain.PurchaseOrderStatus;
import com.nammamedmate.inventory.domain.ReorderSuggestionSnapshot;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PharmacyReorderService {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final int WINDOW = 60;
  private static final int LIST_LIMIT = 30;
  private static final int CREATE_LIMIT = 20;
  private static final int PO_LIST_LIMIT = 60;
  private static final int PATCH_LIMIT = 30;
  private static final int SEND_LIMIT = 10;
  private static final int GRN_LIMIT = 10;
  private static final int REFRESH_BURST_WINDOW = 300;

  private final ReorderSuggestionStore suggestionStore;
  private final PurchaseOrderStore poStore;
  private final DistributorStore distributorStore;
  private final PharmacyProductStore productStore;
  private final PurchaseGrnStore grnStore;
  private final InventoryPlanPort planPort;
  private final OutboxPublisher outbox;
  private final RateLimiter rateLimiter;
  private final Clock clock;
  private final boolean whatsappEnabled;
  private final boolean emailEnabled;

  public PharmacyReorderService(
      ReorderSuggestionStore suggestionStore,
      PurchaseOrderStore poStore,
      DistributorStore distributorStore,
      PharmacyProductStore productStore,
      PurchaseGrnStore grnStore,
      InventoryPlanPort planPort,
      OutboxPublisher outbox,
      RateLimiter rateLimiter,
      Clock clock,
      @Value("${medmate.inventory.po-dispatch.whatsapp-enabled:false}") boolean whatsappEnabled,
      @Value("${medmate.inventory.po-dispatch.email-enabled:true}") boolean emailEnabled) {
    this.suggestionStore = suggestionStore;
    this.poStore = poStore;
    this.distributorStore = distributorStore;
    this.productStore = productStore;
    this.grnStore = grnStore;
    this.planPort = planPort;
    this.outbox = outbox;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.whatsappEnabled = whatsappEnabled;
    this.emailEnabled = emailEnabled;
  }

  public record ListPage(Map<String, Object> data, PaginationMeta meta) {
    public ListPage {
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  @Transactional(readOnly = true)
  public ListPage listSuggestions(
      MedmatePrincipal principal, String groupBy, Integer page, Integer limit) {
    requireGrowth();
    requirePharmacyReader(principal);
    rateLimit("pharmacy:reorder:list:" + principal.pharmacyId(), LIST_LIMIT);

    int p = page == null ? 1 : Math.max(page, 1);
    int lim = limit == null ? 50 : Math.min(Math.max(limit, 1), 200);
    String group =
        groupBy == null || groupBy.isBlank()
            ? "distributor"
            : groupBy.trim().toLowerCase(Locale.ROOT);
    if (!group.equals("distributor") && !group.equals("urgency")) {
      throw new AppException("VALIDATION_ERROR", "group_by must be distributor or urgency", 400);
    }

    UUID pharmacyId = principal.pharmacyId();
    var result = suggestionStore.listLatest(pharmacyId, p, lim);
    long openPos = poStore.countOpen(pharmacyId);
    Instant refreshed = suggestionStore.latestRefreshedAt(pharmacyId).orElse(null);

    BigDecimal estimatedSavings = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    List<Map<String, Object>> itemMaps = new ArrayList<>();
    for (SuggestionRow row : result.rows()) {
      Map<String, Object> item = toSuggestionItem(pharmacyId, row);
      estimatedSavings = estimatedSavings.add((BigDecimal) item.get("line_savings"));
      item.remove("line_savings");
      itemMaps.add(item);
    }

    long distributors =
        itemMaps.stream()
            .map(m -> m.get("best_distributor_id"))
            .filter(id -> id != null)
            .distinct()
            .count();

    Map<String, Object> kpi = new LinkedHashMap<>();
    kpi.put("items_below_reorder_level", result.total());
    kpi.put("distributors_to_order_from", distributors);
    kpi.put("estimated_savings", estimatedSavings);
    kpi.put("open_pos_count", openPos);
    kpi.put("last_refreshed_at", refreshed == null ? null : refreshed.toString());

    List<Map<String, Object>> groups =
        group.equals("urgency") ? groupByUrgency(itemMaps) : groupByDistributor(itemMaps);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("kpi", kpi);
    data.put("suggestion_groups", groups);
    return new ListPage(data, PaginationMeta.of(p, lim, result.total()));
  }

  @Transactional
  public Map<String, Object> createPo(
      MedmatePrincipal principal, UUID distributorId, List<Map<String, Object>> items) {
    requireGrowth();
    requirePharmacyReader(principal);
    rateLimit("pharmacy:reorder:create-po:" + principal.pharmacyId(), CREATE_LIMIT);

    if (items == null || items.isEmpty()) {
      throw new AppException("EMPTY_ITEMS_LIST", "items array is empty", 400);
    }

    Distributor distributor =
        distributorStore
            .findById(principal.pharmacyId(), distributorId)
            .orElseThrow(
                () -> new AppException("DISTRIBUTOR_NOT_FOUND", "Distributor not found", 404));
    if (!distributor.usable()) {
      throw new AppException("DISTRIBUTOR_INACTIVE", "Distributor is deactivated", 400);
    }

    Instant now = clock.instant();
    LocalDate todayIst = LocalDate.ofInstant(now, IST);
    YearMonth ym = YearMonth.from(todayIst);
    int seq = poStore.nextSequence(principal.pharmacyId(), ym);
    String poNumber = String.format("PO-%04d-%02d-%06d", ym.getYear(), ym.getMonthValue(), seq);

    PurchaseOrder po =
        new PurchaseOrder(
            UUID.randomUUID(),
            principal.pharmacyId(),
            distributorId,
            poNumber,
            PurchaseOrderStatus.DRAFT,
            principal.subject(),
            null,
            null,
            null,
            now,
            now,
            null);
    poStore.insert(po);

    int count = 0;
    long totalPaise = 0L;
    for (Map<String, Object> raw : items) {
      UUID productId = parseUuid(raw.get("product_id"), "product_id");
      int qty = parsePositiveInt(raw.get("quantity"), "quantity");
      PharmacyProduct product =
          productStore
              .findById(principal.pharmacyId(), productId)
              .orElseThrow(() -> new AppException("PRODUCT_NOT_FOUND", "Product not found", 404));

      Long estimatedPaise = bestLandedPaise(principal.pharmacyId(), productId, distributorId);
      PurchaseOrderItem item =
          new PurchaseOrderItem(
              UUID.randomUUID(),
              po.id(),
              principal.pharmacyId(),
              product.id(),
              qty,
              estimatedPaise,
              now);
      poStore.insertItem(item);
      count++;
      if (estimatedPaise != null) {
        totalPaise = Math.addExact(totalPaise, Math.multiplyExact(estimatedPaise, qty));
      }
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("po_id", po.id().toString());
    data.put("po_number", po.poNumber());
    data.put("distributor_id", distributor.id().toString());
    data.put("distributor_name", distributor.firmName());
    data.put("items_count", count);
    data.put("estimated_total", paiseToRupees(totalPaise));
    data.put("status", PurchaseOrderStatus.DRAFT.name());
    data.put("created_at", now.toString());
    return data;
  }

  @Transactional(readOnly = true)
  public ListPage listPurchaseOrders(
      MedmatePrincipal principal, String status, UUID distributorId, Integer page, Integer limit) {
    requireGrowth();
    requirePharmacyReader(principal);
    rateLimit("pharmacy:reorder:po-list:" + principal.pharmacyId(), PO_LIST_LIMIT);

    int p = page == null ? 1 : Math.max(page, 1);
    int lim = limit == null ? 20 : Math.min(Math.max(limit, 1), 100);
    PurchaseOrderStatus statusFilter = parseStatusOptional(status);

    ListResult result =
        poStore.list(new ListFilter(principal.pharmacyId(), statusFilter, distributorId, p, lim));

    List<Map<String, Object>> orders = new ArrayList<>(result.rows().size());
    for (PoListRow row : result.rows()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("po_id", row.poId().toString());
      m.put("po_number", row.poNumber());
      m.put("distributor_name", row.distributorName());
      m.put("items_count", row.itemsCount());
      m.put("estimated_total", paiseToRupees(row.estimatedTotalPaise()));
      m.put("status", row.status().name());
      m.put("created_at", row.createdAt().toString());
      m.put("sent_at", row.sentAt() == null ? null : row.sentAt().toString());
      orders.add(m);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("purchase_orders", orders);
    return new ListPage(data, PaginationMeta.of(p, lim, result.total()));
  }

  @Transactional
  public Map<String, Object> patchPo(
      MedmatePrincipal principal,
      UUID poId,
      List<Map<String, Object>> addItems,
      List<UUID> removeItemIds,
      List<Map<String, Object>> updateItems) {
    requireGrowth();
    requirePharmacyReader(principal);
    rateLimit("pharmacy:reorder:po-patch:" + principal.pharmacyId(), PATCH_LIMIT);

    PurchaseOrder po = requirePo(principal.pharmacyId(), poId);
    if (!po.editable()) {
      throw new AppException("PO_NOT_EDITABLE", "PO status is SENT or RECEIVED", 400);
    }

    Instant now = clock.instant();
    if (removeItemIds != null) {
      for (UUID itemId : removeItemIds) {
        if (itemId != null) {
          poStore.deleteItem(principal.pharmacyId(), poId, itemId);
        }
      }
    }
    if (updateItems != null) {
      for (Map<String, Object> u : updateItems) {
        UUID itemId = parseUuid(u.get("item_id"), "item_id");
        int qty = parsePositiveInt(u.get("quantity"), "quantity");
        poStore
            .findItem(principal.pharmacyId(), poId, itemId)
            .orElseThrow(() -> new AppException("PO_ITEM_NOT_FOUND", "PO item not found", 404));
        poStore.updateItemQuantity(itemId, qty);
      }
    }
    if (addItems != null) {
      for (Map<String, Object> raw : addItems) {
        UUID productId = parseUuid(raw.get("product_id"), "product_id");
        int qty = parsePositiveInt(raw.get("quantity"), "quantity");
        productStore
            .findById(principal.pharmacyId(), productId)
            .orElseThrow(() -> new AppException("PRODUCT_NOT_FOUND", "Product not found", 404));
        Long estimatedPaise =
            bestLandedPaise(principal.pharmacyId(), productId, po.distributorId());
        poStore.insertItem(
            new PurchaseOrderItem(
                UUID.randomUUID(),
                poId,
                principal.pharmacyId(),
                productId,
                qty,
                estimatedPaise,
                now));
      }
    }

    poStore.update(poId, po.status(), po.sentAt(), po.sentChannel(), po.grnId(), now);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("po_id", poId.toString());
    data.put("items_count", poStore.countItems(principal.pharmacyId(), poId));
    data.put(
        "estimated_total",
        paiseToRupees(poStore.estimatedTotalPaise(principal.pharmacyId(), poId)));
    data.put("updated_at", now.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> sendPo(
      MedmatePrincipal principal, UUID poId, String channel, String recipientOverride) {
    requireGrowth();
    requirePharmacyReader(principal);
    if (principal.role() != AuthRole.PHARMACY_OWNER) {
      throw new AppException("STAFF_CANNOT_SEND_PO", "Only pharmacy_owner may send POs", 403);
    }
    rateLimit("pharmacy:reorder:po-send:" + principal.pharmacyId(), SEND_LIMIT);

    PurchaseOrder po = requirePo(principal.pharmacyId(), poId);
    if (po.status() == PurchaseOrderStatus.SENT || po.status() == PurchaseOrderStatus.RECEIVED) {
      throw new AppException("PO_ALREADY_SENT", "PO already in SENT status", 400);
    }
    if (po.status() != PurchaseOrderStatus.DRAFT) {
      throw new AppException("PO_NOT_EDITABLE", "PO cannot be sent", 400);
    }
    if (poStore.countItems(principal.pharmacyId(), poId) == 0) {
      throw new AppException("EMPTY_PO", "PO has no items", 400);
    }

    PoSentChannel sentChannel = parseChannel(channel);
    if (sentChannel == PoSentChannel.WHATSAPP && !whatsappEnabled) {
      throw new AppException(
          "CHANNEL_UNAVAILABLE", "WhatsApp service temporarily unavailable", 503);
    }
    if (sentChannel == PoSentChannel.EMAIL && !emailEnabled) {
      throw new AppException("CHANNEL_UNAVAILABLE", "Email service temporarily unavailable", 503);
    }

    Distributor distributor =
        distributorStore
            .findById(principal.pharmacyId(), po.distributorId())
            .orElseThrow(
                () -> new AppException("DISTRIBUTOR_NOT_FOUND", "Distributor not found", 404));

    String sentTo = resolveRecipient(sentChannel, distributor, recipientOverride);
    Instant now = clock.instant();

    List<ItemWithProduct> items = poStore.listItems(principal.pharmacyId(), poId);
    List<Map<String, Object>> pdfRows = new ArrayList<>();
    for (ItemWithProduct row : items) {
      Map<String, Object> line = new LinkedHashMap<>();
      line.put("product_name", row.productName());
      line.put("batch_number", "");
      line.put("expiry_date", "");
      line.put("quantity_current", row.item().quantity());
      line.put(
          "value_at_risk",
          row.item().estimatedPricePaise() == null
              ? "0"
              : paiseToRupees(row.item().estimatedPricePaise()).toPlainString());
      pdfRows.add(line);
    }
    byte[] pdf = SimplePdfExporter.export("PO " + po.poNumber(), pdfRows);
    String pdfUrl = "data:application/pdf;base64," + Base64.getEncoder().encodeToString(pdf);

    PurchaseOrder updated =
        poStore.update(poId, PurchaseOrderStatus.SENT, now, sentChannel, null, now);

    outbox.publish(
        DomainEvent.of(
            "inventory.po.sent",
            "purchase_order",
            poId,
            Map.of(
                "pharmacy_id", principal.pharmacyId().toString(),
                "po_id", poId.toString(),
                "distributor_id", po.distributorId().toString(),
                "channel", sentChannel.name())));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("po_id", updated.id().toString());
    data.put("po_number", updated.poNumber());
    data.put("status", PurchaseOrderStatus.SENT.name());
    data.put("channel", sentChannel.name());
    data.put("sent_to", sentTo);
    data.put("sent_at", now.toString());
    data.put("pdf_url", pdfUrl);
    return data;
  }

  @Transactional
  public Map<String, Object> recordGrn(
      MedmatePrincipal principal, UUID poId, String invoiceNumber, LocalDate invoiceDate) {
    requireGrowth();
    requirePharmacyReader(principal);
    rateLimit("pharmacy:reorder:record-grn:" + principal.pharmacyId(), GRN_LIMIT);

    PurchaseOrder po = requirePo(principal.pharmacyId(), poId);
    if (po.status() != PurchaseOrderStatus.SENT) {
      throw new AppException("PO_NOT_SENT", "PO must be in SENT status to record GRN", 400);
    }
    if (invoiceNumber == null || invoiceNumber.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "invoice_number is required", 400);
    }
    if (invoiceDate == null) {
      throw new AppException("VALIDATION_ERROR", "invoice_date is required", 400);
    }
    if (grnStore.invoiceExists(principal.pharmacyId(), po.distributorId(), invoiceNumber.trim())) {
      throw new AppException("DUPLICATE_INVOICE_NUMBER", "Invoice already recorded", 400);
    }

    Instant now = clock.instant();
    PurchaseGrn grn =
        new PurchaseGrn(
            UUID.randomUUID(),
            principal.pharmacyId(),
            po.distributorId(),
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

    List<ItemWithProduct> items = poStore.listItems(principal.pharmacyId(), poId);
    LocalDate defaultExpiry = LocalDate.ofInstant(now, IST).plusYears(1);
    int prefills = 0;
    for (ItemWithProduct row : items) {
      long purchasePaise =
          row.item().estimatedPricePaise() == null || row.item().estimatedPricePaise() <= 0
              ? Math.max(row.mrpPaise(), 1L)
              : row.item().estimatedPricePaise();
      long mrpPaise = Math.max(row.mrpPaise(), purchasePaise);
      int gstPct = row.gstPct();
      long taxable = PurchaseGrnItem.taxablePaise(row.item().quantity(), purchasePaise);
      long gst = PurchaseGrnItem.gstPaise(taxable, gstPct);
      long lineTotal = PurchaseGrnItem.lineTotalPaise(taxable, gst);
      grnStore.insertItem(
          new PurchaseGrnItem(
              UUID.randomUUID(),
              grn.id(),
              principal.pharmacyId(),
              row.item().productId(),
              "PENDING",
              defaultExpiry,
              null,
              row.item().quantity(),
              0,
              purchasePaise,
              mrpPaise,
              gstPct,
              taxable,
              gst,
              lineTotal,
              false,
              now,
              now));
      prefills++;
    }

    poStore.update(
        poId, PurchaseOrderStatus.RECEIVED, po.sentAt(), po.sentChannel(), grn.id(), now);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("grn_id", grn.id().toString());
    data.put("grn_status", GrnStatus.DRAFT.name());
    data.put("po_id", poId.toString());
    data.put("prefilled_items_count", prefills);
    data.put(
        "message",
        "GRN created in DRAFT with PO items pre-filled. Review and finalize to update stock.");
    return data;
  }

  @Transactional
  public Map<String, Object> refresh(MedmatePrincipal principal) {
    requireGrowth();
    requireOwner(principal);
    if (!rateLimiter.tryAcquire(
        "pharmacy:reorder:refresh:" + principal.pharmacyId(), 1, REFRESH_BURST_WINDOW)) {
      throw new AppException("RATE_LIMITED", "Refresh allowed once per 5 minutes", 429);
    }

    Instant now = clock.instant();
    int count = refreshPharmacy(principal.pharmacyId(), now);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("refreshed_at", now.toString());
    data.put("items_below_reorder_level", count);
    return data;
  }

  /** Nightly / manual rebuild of suggestion snapshots for one pharmacy. */
  @Transactional
  public int refreshPharmacy(UUID pharmacyId, Instant now) {
    LocalDate snapshotDate = LocalDate.ofInstant(now, IST);
    List<LowStockProduct> products = suggestionStore.listLowStockProducts(pharmacyId);
    List<ReorderSuggestionSnapshot> rows = new ArrayList<>(products.size());
    for (LowStockProduct product : products) {
      List<SupplyOffer> offers = suggestionStore.listActiveOffers(pharmacyId, product.productId());
      SupplyOffer best = pickBestOffer(offers);
      BigDecimal daysOfCover = daysOfCover(product.currentStock());
      Long landedPaise = null;
      UUID bestId = null;
      if (best != null) {
        bestId = best.distributorId();
        landedPaise =
            DistributorFormats.effectiveLandedCostPaise(
                    best.purchasePricePaise(), best.schemeDescription())
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
      }
      rows.add(
          new ReorderSuggestionSnapshot(
              UUID.randomUUID(),
              pharmacyId,
              product.productId(),
              product.currentStock(),
              product.reorderLevel(),
              daysOfCover,
              bestId,
              landedPaise,
              snapshotDate,
              now));
    }
    suggestionStore.replaceSnapshots(pharmacyId, snapshotDate, rows);
    return rows.size();
  }

  @Transactional
  public void refreshAllPharmacies() {
    Instant now = clock.instant();
    for (UUID pharmacyId : suggestionStore.listPharmacyIdsWithLowStock()) {
      refreshPharmacy(pharmacyId, now);
    }
  }

  static int suggestedQuantity(int currentStock, int reorderLevel) {
    // ponytail: bring stock to 2× reorder_level until demand-based formula exists (POS).
    return Math.max(reorderLevel * 2 - currentStock, 1);
  }

  static BigDecimal daysOfCover(int currentStock) {
    // avg_daily_units_sold_30d stubbed 0 until POS → always null.
    return null;
  }

  private Map<String, Object> toSuggestionItem(UUID pharmacyId, SuggestionRow row) {
    ReorderSuggestionSnapshot snap = row.snapshot();
    int suggested = suggestedQuantity(snap.currentStock(), snap.reorderLevel());
    List<SupplyOffer> offers = suggestionStore.listActiveOffers(pharmacyId, snap.productId());
    SupplyOffer best = pickBestOffer(offers);
    SupplyOffer alt = pickAlternative(offers, best);

    BigDecimal landed =
        snap.landedPricePaise() == null ? null : paiseToRupees(snap.landedPricePaise());
    BigDecimal altLanded = null;
    BigDecimal savingsPerPack = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    BigDecimal lineSavings = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    Map<String, Object> alternative = null;
    if (best != null && alt != null) {
      BigDecimal bestLanded =
          DistributorFormats.effectiveLandedCostPaise(
              best.purchasePricePaise(), best.schemeDescription());
      altLanded =
          DistributorFormats.effectiveLandedCostPaise(
              alt.purchasePricePaise(), alt.schemeDescription());
      savingsPerPack =
          altLanded.subtract(bestLanded).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
      lineSavings =
          savingsPerPack.multiply(BigDecimal.valueOf(suggested)).setScale(2, RoundingMode.HALF_UP);
      alternative = new LinkedHashMap<>();
      alternative.put("name", alt.distributorName());
      alternative.put("landed_price", altLanded);
    }

    Map<String, Object> item = new LinkedHashMap<>();
    item.put("product_id", snap.productId().toString());
    item.put("product_name", row.productName());
    item.put("manufacturer", row.manufacturer());
    item.put("current_stock", snap.currentStock());
    item.put("reorder_level", snap.reorderLevel());
    item.put("days_of_cover", snap.daysOfCover());
    item.put(
        "best_distributor_id",
        snap.bestDistributorId() == null ? null : snap.bestDistributorId().toString());
    item.put("best_distributor_name", row.bestDistributorName());
    item.put("best_distributor_phone", row.bestDistributorPhone());
    item.put("landed_price", landed);
    item.put("best_price_badge", best != null);
    item.put("savings_per_pack", savingsPerPack);
    item.put("suggested_quantity", suggested);
    item.put("line_savings", lineSavings);
    item.put("alternative_distributor", alternative);
    return item;
  }

  private static List<Map<String, Object>> groupByDistributor(List<Map<String, Object>> items) {
    Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
    for (Map<String, Object> item : items) {
      String key =
          item.get("best_distributor_id") == null
              ? "_none"
              : item.get("best_distributor_id").toString();
      Map<String, Object> group =
          groups.computeIfAbsent(
              key,
              k -> {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("distributor_id", item.get("best_distributor_id"));
                g.put(
                    "distributor_name",
                    item.get("best_distributor_name") == null
                        ? "no distributor linked"
                        : item.get("best_distributor_name"));
                g.put("distributor_phone", item.get("best_distributor_phone"));
                g.put("items_count", 0);
                g.put("estimated_po_value", BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
                g.put("items", new ArrayList<Map<String, Object>>());
                return g;
              });
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> groupItems = (List<Map<String, Object>>) group.get("items");
      Map<String, Object> copy = new LinkedHashMap<>(item);
      copy.remove("best_distributor_id");
      copy.remove("best_distributor_phone");
      groupItems.add(copy);
      group.put("items_count", groupItems.size());
      BigDecimal landed = (BigDecimal) item.get("landed_price");
      int qty = (Integer) item.get("suggested_quantity");
      if (landed != null) {
        BigDecimal value = (BigDecimal) group.get("estimated_po_value");
        group.put(
            "estimated_po_value",
            value.add(landed.multiply(BigDecimal.valueOf(qty))).setScale(2, RoundingMode.HALF_UP));
      }
    }
    return new ArrayList<>(groups.values());
  }

  private static List<Map<String, Object>> groupByUrgency(List<Map<String, Object>> items) {
    Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
    for (String urgency : List.of("OUT_OF_STOCK", "BELOW_REORDER")) {
      Map<String, Object> g = new LinkedHashMap<>();
      g.put("urgency", urgency);
      g.put("items_count", 0);
      g.put("items", new ArrayList<Map<String, Object>>());
      groups.put(urgency, g);
    }
    for (Map<String, Object> item : items) {
      int stock = (Integer) item.get("current_stock");
      String urgency = stock == 0 ? "OUT_OF_STOCK" : "BELOW_REORDER";
      Map<String, Object> group = groups.get(urgency);
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> groupItems = (List<Map<String, Object>>) group.get("items");
      Map<String, Object> copy = new LinkedHashMap<>(item);
      copy.remove("best_distributor_id");
      copy.remove("best_distributor_phone");
      groupItems.add(copy);
      group.put("items_count", groupItems.size());
    }
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> g : groups.values()) {
      if ((Integer) g.get("items_count") > 0) {
        out.add(g);
      }
    }
    return out;
  }

  private static SupplyOffer pickBestOffer(List<SupplyOffer> offers) {
    if (offers == null || offers.isEmpty()) {
      return null;
    }
    return offers.stream()
        .min(
            Comparator.comparing(
                o ->
                    DistributorFormats.effectiveLandedCostPaise(
                        o.purchasePricePaise(), o.schemeDescription())))
        .orElse(null);
  }

  private static SupplyOffer pickAlternative(List<SupplyOffer> offers, SupplyOffer best) {
    if (offers == null || best == null) {
      return null;
    }
    return offers.stream()
        .filter(o -> !o.distributorId().equals(best.distributorId()))
        .min(
            Comparator.comparing(
                o ->
                    DistributorFormats.effectiveLandedCostPaise(
                        o.purchasePricePaise(), o.schemeDescription())))
        .orElse(null);
  }

  private Long bestLandedPaise(UUID pharmacyId, UUID productId, UUID distributorId) {
    return suggestionStore.listActiveOffers(pharmacyId, productId).stream()
        .filter(o -> o.distributorId().equals(distributorId))
        .findFirst()
        .map(
            o ->
                DistributorFormats.effectiveLandedCostPaise(
                        o.purchasePricePaise(), o.schemeDescription())
                    .movePointRight(2)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact())
        .orElse(null);
  }

  private PurchaseOrder requirePo(UUID pharmacyId, UUID poId) {
    return poStore
        .findById(pharmacyId, poId)
        .orElseThrow(() -> new AppException("PO_NOT_FOUND", "PO ID not found", 404));
  }

  private void requireGrowth() {
    if (!planPort.growthFeaturesEnabled()) {
      throw new AppException(
          "PLAN_FEATURE_LOCKED", "Reorder suggestions require Growth plan or higher", 403);
    }
  }

  private void rateLimit(String key, int limit) {
    if (!rateLimiter.tryAcquire(key, limit, WINDOW)) {
      throw new AppException("RATE_LIMITED", "Too many requests", 429);
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
      throw new AppException("FORBIDDEN", "Pharmacy context required", 403);
    }
  }

  private static void requireOwner(MedmatePrincipal principal) {
    requirePharmacyReader(principal);
    if (principal.role() != AuthRole.PHARMACY_OWNER) {
      throw new AppException("FORBIDDEN", "Only pharmacy_owner may perform this action", 403);
    }
  }

  private static PoSentChannel parseChannel(String channel) {
    if (channel == null || channel.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "channel is required", 400);
    }
    try {
      return PoSentChannel.valueOf(channel.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new AppException("VALIDATION_ERROR", "channel must be WHATSAPP or EMAIL", 400);
    }
  }

  private static String resolveRecipient(
      PoSentChannel channel, Distributor distributor, String override) {
    if (override != null && !override.isBlank()) {
      return override.trim();
    }
    if (channel == PoSentChannel.WHATSAPP) {
      if (distributor.phone() == null || distributor.phone().isBlank()) {
        throw new AppException("VALIDATION_ERROR", "Distributor has no phone for WhatsApp", 400);
      }
      return distributor.phone();
    }
    if (distributor.email() == null || distributor.email().isBlank()) {
      throw new AppException("VALIDATION_ERROR", "Distributor has no email", 400);
    }
    return distributor.email();
  }

  private static PurchaseOrderStatus parseStatusOptional(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    try {
      return PurchaseOrderStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new AppException("VALIDATION_ERROR", "Invalid PO status", 400);
    }
  }

  private static UUID parseUuid(Object raw, String field) {
    if (raw == null) {
      throw new AppException("VALIDATION_ERROR", field + " is required", 400);
    }
    try {
      return UUID.fromString(raw.toString());
    } catch (IllegalArgumentException e) {
      throw new AppException("VALIDATION_ERROR", field + " must be a UUID", 400);
    }
  }

  private static int parsePositiveInt(Object raw, String field) {
    if (raw == null) {
      throw new AppException("VALIDATION_ERROR", field + " is required", 400);
    }
    final int value;
    try {
      value = Integer.parseInt(raw.toString().trim());
    } catch (NumberFormatException e) {
      throw new AppException("VALIDATION_ERROR", field + " must be an integer", 400);
    }
    if (value <= 0) {
      throw new AppException("VALIDATION_ERROR", field + " must be > 0", 400);
    }
    return value;
  }

  static BigDecimal paiseToRupees(long paise) {
    return BigDecimal.valueOf(paise).movePointLeft(2);
  }
}
