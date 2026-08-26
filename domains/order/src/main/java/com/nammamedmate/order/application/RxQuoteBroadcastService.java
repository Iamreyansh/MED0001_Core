package com.nammamedmate.order.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.order.application.port.out.CustomerAddressPort;
import com.nammamedmate.order.application.port.out.CustomerAddressPort.AddressRow;
import com.nammamedmate.order.application.port.out.PharmacyCandidatePort;
import com.nammamedmate.order.application.port.out.PharmacyCandidatePort.PharmacyRow;
import com.nammamedmate.order.application.port.out.PrescriptionPort;
import com.nammamedmate.order.application.port.out.PrescriptionPort.MedicineLine;
import com.nammamedmate.order.application.port.out.PrescriptionPort.PrescriptionDetail;
import com.nammamedmate.order.application.port.out.RxBroadcastStore;
import com.nammamedmate.order.domain.Cart;
import com.nammamedmate.order.domain.CartItem;
import com.nammamedmate.order.domain.CartPricing;
import com.nammamedmate.order.domain.Haversine;
import com.nammamedmate.order.domain.QuotedMedicine;
import com.nammamedmate.order.domain.RxBroadcast;
import com.nammamedmate.order.domain.RxBroadcast.RequestedMedicine;
import com.nammamedmate.order.domain.RxBroadcastPharmacy;
import com.nammamedmate.order.domain.RxBroadcastStatus;
import com.nammamedmate.order.domain.RxPharmacySlotStatus;
import com.nammamedmate.order.domain.RxQuotePricing;
import com.nammamedmate.order.domain.RxQuotePricing.QuoteBill;
import com.nammamedmate.order.domain.RxQuoteTags;
import com.nammamedmate.order.domain.RxQuoteTags.TaggedQuote;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RxQuoteBroadcastService {

  public static final double BROADCAST_RADIUS_KM = 3.0;
  public static final int MAX_PHARMACIES = 10;
  public static final Duration PHARMACY_RESPONSE_WINDOW = Duration.ofMinutes(15);
  public static final Duration QUOTE_TTL = Duration.ofMinutes(20);
  public static final Duration BROADCAST_TTL = Duration.ofMinutes(30);

  private final RxBroadcastStore broadcasts;
  private final PharmacyCandidatePort pharmacies;
  private final CustomerAddressPort addresses;
  private final PrescriptionPort prescriptions;
  private final CartService cartService;
  private final OutboxPublisher outbox;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public RxQuoteBroadcastService(
      RxBroadcastStore broadcasts,
      PharmacyCandidatePort pharmacies,
      CustomerAddressPort addresses,
      PrescriptionPort prescriptions,
      CartService cartService,
      OutboxPublisher outbox,
      RateLimiter rateLimiter,
      Clock clock) {
    this.broadcasts = broadcasts;
    this.pharmacies = pharmacies;
    this.addresses = addresses;
    this.prescriptions = prescriptions;
    this.cartService = cartService;
    this.outbox = outbox;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  @Transactional
  public Map<String, Object> broadcast(
      MedmatePrincipal principal,
      UUID prescriptionId,
      UUID deliveryAddressId,
      String patientName,
      String notes) {
    requireCustomer(principal);
    rateLimit("order:rx-quote-broadcast:" + principal.subject(), 5, 60);
    if (prescriptionId == null) {
      throw new AppException("VALIDATION_ERROR", "prescription_id is required", 400);
    }
    if (deliveryAddressId == null) {
      throw new AppException("VALIDATION_ERROR", "delivery_address_id is required", 400);
    }
    if (patientName == null || patientName.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "patient_name is required", 400);
    }
    String trimmedNotes = notes == null || notes.isBlank() ? null : notes.trim();
    if (trimmedNotes != null && trimmedNotes.length() > 300) {
      throw new AppException("VALIDATION_ERROR", "notes max 300 characters", 400);
    }

    PrescriptionDetail rx =
        prescriptions
            .findForBroadcast(prescriptionId, principal.subject())
            .orElseThrow(
                () -> new AppException("PRESCRIPTION_NOT_FOUND", "Prescription not found", 404));
    if (rx.expired()) {
      throw new AppException("PRESCRIPTION_EXPIRED", "Prescription has expired", 422);
    }

    AddressRow address =
        addresses
            .findForCustomer(deliveryAddressId, principal.subject())
            .orElseThrow(() -> new AppException("ADDRESS_NOT_FOUND", "Address not found", 404));

    List<PharmacyRow> near =
        pharmacies.findOpenNear(address.lat(), address.lng(), BROADCAST_RADIUS_KM);
    List<Ranked> ranked = new ArrayList<>();
    for (PharmacyRow row : near) {
      if (row.latitude() == null || row.longitude() == null) {
        continue;
      }
      double d =
          Haversine.distanceKm(address.lat(), address.lng(), row.latitude(), row.longitude());
      if (d <= BROADCAST_RADIUS_KM) {
        ranked.add(new Ranked(row, d));
      }
    }
    ranked.sort(Comparator.comparingDouble(Ranked::distanceKm));
    if (ranked.isEmpty()) {
      throw new AppException("NO_PHARMACIES_NEARBY", "No eligible pharmacies within 3km", 422);
    }
    if (ranked.size() > MAX_PHARMACIES) {
      ranked = new ArrayList<>(ranked.subList(0, MAX_PHARMACIES));
    }

    Instant now = now();
    UUID broadcastId = UUID.randomUUID();
    List<RequestedMedicine> requested = toRequested(rx.medicines());
    RxBroadcast broadcast =
        new RxBroadcast(
            broadcastId,
            principal.subject(),
            prescriptionId,
            deliveryAddressId,
            patientName.trim(),
            trimmedNotes,
            requested,
            RxBroadcastStatus.ACTIVE,
            ranked.size(),
            now,
            now.plus(BROADCAST_TTL),
            null,
            null,
            now);

    List<RxBroadcastPharmacy> slots = new ArrayList<>();
    for (Ranked r : ranked) {
      slots.add(
          new RxBroadcastPharmacy(
              UUID.randomUUID(),
              broadcastId,
              r.row().id(),
              roundKm(r.distanceKm()),
              RxPharmacySlotStatus.NOTIFIED,
              null,
              null,
              null,
              now,
              now.plus(PHARMACY_RESPONSE_WINDOW),
              null,
              null,
              List.of()));
    }
    broadcasts.insert(broadcast, slots);

    for (RxBroadcastPharmacy slot : slots) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("broadcast_id", broadcastId.toString());
      payload.put("pharmacy_id", slot.pharmacyId().toString());
      payload.put("patient_name", broadcast.patientName());
      payload.put("distance_km", slot.distanceKm());
      payload.put("response_deadline", slot.responseDeadline().toString());
      outbox.publish(
          DomainEvent.of("order.rx_quote.pharmacy_notified", "rx_broadcast", broadcastId, payload));
    }

    return broadcastCreatedView(broadcast, slots);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getBroadcast(MedmatePrincipal principal, UUID broadcastId) {
    requireCustomer(principal);
    rateLimit("order:rx-quote-get:" + principal.subject(), 60, 60);
    RxBroadcast broadcast = requireCustomerBroadcast(broadcastId, principal.subject());
    List<RxBroadcastPharmacy> slots = broadcasts.listPharmacies(broadcastId);
    int quotes = countQuoted(slots);
    boolean canView = RxQuoteTags.canViewQuotes(quotes, broadcast.broadcastAt(), now());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("broadcast_id", broadcast.id());
    data.put("status", broadcast.status().name());
    data.put("broadcast_at", broadcast.broadcastAt().toString());
    data.put("expires_at", broadcast.expiresAt().toString());
    data.put("pharmacies_notified", broadcast.pharmaciesNotified());
    data.put("quotes_received", quotes);
    data.put("can_view_quotes", canView);
    data.put("pharmacies", pharmacyStatusList(slots, canView));
    return data;
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> listQuotes(MedmatePrincipal principal, UUID broadcastId) {
    requireCustomer(principal);
    rateLimit("order:rx-quote-list:" + principal.subject(), 30, 60);
    RxBroadcast broadcast = requireCustomerBroadcast(broadcastId, principal.subject());
    List<RxBroadcastPharmacy> slots = broadcasts.listPharmacies(broadcastId);
    int quotes = countQuoted(slots);
    if (!RxQuoteTags.canViewQuotes(quotes, broadcast.broadcastAt(), now())) {
      return List.of();
    }
    return quoteViews(broadcast, slots);
  }

  @Transactional
  public Map<String, Object> selectQuote(
      MedmatePrincipal principal, UUID broadcastId, UUID pharmacyId) {
    requireCustomer(principal);
    rateLimit("order:rx-quote-select:" + principal.subject(), 5, 60);
    if (pharmacyId == null) {
      throw new AppException("VALIDATION_ERROR", "pharmacy_id is required", 400);
    }
    RxBroadcast broadcast = requireCustomerBroadcast(broadcastId, principal.subject());
    Instant now = now();
    if (broadcast.status() == RxBroadcastStatus.EXPIRED || !now.isBefore(broadcast.expiresAt())) {
      throw new AppException("BROADCAST_EXPIRED", "Broadcast has expired", 422);
    }
    if (broadcast.status() == RxBroadcastStatus.SELECTED) {
      throw new AppException("BROADCAST_EXPIRED", "Broadcast already selected", 422);
    }
    RxBroadcastPharmacy slot =
        broadcasts
            .findPharmacySlot(broadcastId, pharmacyId)
            .orElseThrow(() -> new AppException("QUOTE_NOT_FOUND", "No quote from pharmacy", 404));
    if (slot.status() != RxPharmacySlotStatus.QUOTED || slot.medicinesAvailable() == null) {
      throw new AppException("QUOTE_NOT_FOUND", "No quote from pharmacy", 404);
    }
    if (slot.quoteExpired(now)) {
      throw new AppException("QUOTE_EXPIRED", "The pharmacy's quote has expired", 422);
    }

    PharmacyRow pharmacy =
        pharmacies
            .findById(pharmacyId)
            .orElseThrow(() -> new AppException("QUOTE_NOT_FOUND", "No quote from pharmacy", 404));

    List<CartItem> items = toCartItems(slot.medicinesAvailable());
    Cart cart =
        cartService.createActiveFromQuote(
            principal.subject(),
            pharmacyId,
            broadcast.deliveryAddressId(),
            broadcast.prescriptionId(),
            items);

    broadcasts.markSelected(broadcastId, pharmacyId, cart.id());

    QuoteBill bill = RxQuotePricing.compute(slot.medicinesAvailable());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("broadcast_id", broadcastId);
    data.put("status", RxBroadcastStatus.SELECTED.name());
    data.put("cart_id", cart.id());
    Map<String, Object> cartView = new LinkedHashMap<>();
    Map<String, Object> ph = new LinkedHashMap<>();
    ph.put("id", pharmacy.id());
    ph.put("name", pharmacy.name());
    cartView.put("pharmacy", ph);
    cartView.put("items", quotedItemsView(slot.medicinesAvailable()));
    cartView.put("prescription_id", broadcast.prescriptionId());
    Map<String, Object> billView = new LinkedHashMap<>();
    billView.put("item_total", CartPricing.paiseToRupees(bill.itemTotalPaise()));
    billView.put("delivery_fee", CartPricing.paiseToRupees(bill.deliveryFeePaise()));
    billView.put("handling_fee", CartPricing.paiseToRupees(bill.handlingFeePaise()));
    billView.put("total_payable", CartPricing.paiseToRupees(bill.totalPayablePaise()));
    cartView.put("bill", billView);
    data.put("cart", cartView);
    return data;
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> listIncoming(MedmatePrincipal principal) {
    requirePharmacy(principal);
    rateLimit("order:rx-quote-ph-list:" + principal.subject(), 30, 60);
    UUID pharmacyId = principal.pharmacyId();
    Instant now = now();
    List<Map<String, Object>> out = new ArrayList<>();
    for (RxBroadcastPharmacy slot : broadcasts.listPendingForPharmacy(pharmacyId)) {
      RxBroadcast broadcast = broadcasts.findById(slot.broadcastId()).orElse(null);
      if (broadcast == null) {
        continue;
      }
      long remaining = Math.max(0L, Duration.between(now, slot.responseDeadline()).getSeconds());
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("broadcast_id", slot.broadcastId());
      row.put("patient_name", broadcast.patientName());
      row.put("distance_km", roundKm(slot.distanceKm()));
      row.put("medicines_requested", requestedLabels(broadcast.medicinesRequested()));
      row.put("received_at", slot.receivedAt().toString());
      row.put("response_deadline", slot.responseDeadline().toString());
      row.put("time_remaining_seconds", remaining);
      row.put("status", "PENDING_RESPONSE");
      out.add(row);
    }
    return out;
  }

  @Transactional
  public Map<String, Object> submitQuote(
      MedmatePrincipal principal,
      UUID broadcastId,
      List<Map<String, Object>> medicinesAvailable,
      Integer deliveryEtaMinutes) {
    requirePharmacy(principal);
    rateLimit("order:rx-quote-ph-quote:" + principal.subject(), 10, 60);
    if (deliveryEtaMinutes == null || deliveryEtaMinutes <= 0) {
      throw new AppException("VALIDATION_ERROR", "delivery_eta_minutes must be > 0", 400);
    }
    if (medicinesAvailable == null || medicinesAvailable.isEmpty()) {
      throw new AppException("VALIDATION_ERROR", "medicines_available is required", 400);
    }
    RxBroadcast broadcast =
        broadcasts
            .findById(broadcastId)
            .orElseThrow(() -> new AppException("BROADCAST_NOT_FOUND", "Broadcast not found", 404));
    Instant now = now();
    if (broadcast.status() != RxBroadcastStatus.ACTIVE || !now.isBefore(broadcast.expiresAt())) {
      throw new AppException("BROADCAST_EXPIRED", "Broadcast has expired", 422);
    }
    RxBroadcastPharmacy slot =
        broadcasts
            .findPharmacySlot(broadcastId, principal.pharmacyId())
            .orElseThrow(() -> new AppException("BROADCAST_NOT_FOUND", "Broadcast not found", 404));
    if (slot.status() == RxPharmacySlotStatus.EXPIRED || !now.isBefore(slot.responseDeadline())) {
      throw new AppException("QUOTE_EXPIRED", "Pharmacy response window has expired", 422);
    }
    if (slot.status() == RxPharmacySlotStatus.QUOTED) {
      throw new AppException("VALIDATION_ERROR", "Quote already submitted", 409);
    }
    if (slot.status() == RxPharmacySlotStatus.OUT_OF_STOCK) {
      throw new AppException("VALIDATION_ERROR", "Broadcast already declined", 409);
    }

    List<QuotedMedicine> meds = parseQuotedMedicines(medicinesAvailable);
    QuoteBill bill = RxQuotePricing.compute(meds);
    Instant quoteExpires = now.plus(QUOTE_TTL);
    RxBroadcastPharmacy updated =
        new RxBroadcastPharmacy(
            slot.id(),
            slot.broadcastId(),
            slot.pharmacyId(),
            slot.distanceKm(),
            RxPharmacySlotStatus.QUOTED,
            meds,
            deliveryEtaMinutes,
            bill.totalPayablePaise(),
            slot.receivedAt(),
            slot.responseDeadline(),
            now,
            quoteExpires,
            List.of());
    broadcasts.updatePharmacySlot(updated);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("broadcast_id", broadcastId);
    data.put("status", RxPharmacySlotStatus.QUOTED.name());
    data.put("quote_expires_at", quoteExpires.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> decline(MedmatePrincipal principal, UUID broadcastId, String reason) {
    requirePharmacy(principal);
    rateLimit("order:rx-quote-ph-decline:" + principal.subject(), 10, 60);
    RxBroadcast broadcast =
        broadcasts
            .findById(broadcastId)
            .orElseThrow(() -> new AppException("BROADCAST_NOT_FOUND", "Broadcast not found", 404));
    Instant now = now();
    if (broadcast.status() != RxBroadcastStatus.ACTIVE) {
      throw new AppException("BROADCAST_EXPIRED", "Broadcast has expired", 422);
    }
    RxBroadcastPharmacy slot =
        broadcasts
            .findPharmacySlot(broadcastId, principal.pharmacyId())
            .orElseThrow(() -> new AppException("BROADCAST_NOT_FOUND", "Broadcast not found", 404));
    if (slot.status() == RxPharmacySlotStatus.EXPIRED || !now.isBefore(slot.responseDeadline())) {
      throw new AppException("QUOTE_EXPIRED", "Pharmacy response window has expired", 422);
    }
    if (slot.status() == RxPharmacySlotStatus.QUOTED) {
      throw new AppException("VALIDATION_ERROR", "Quote already submitted", 409);
    }
    broadcasts.updatePharmacyStatus(slot.id(), RxPharmacySlotStatus.OUT_OF_STOCK);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("broadcast_id", broadcastId);
    data.put("status", RxPharmacySlotStatus.OUT_OF_STOCK.name());
    if (reason != null && !reason.isBlank()) {
      data.put("reason", reason.trim());
    }
    return data;
  }

  @Transactional
  public int expirePharmacyResponseWindows() {
    return broadcasts.expirePharmacySlots(now());
  }

  @Transactional
  public int expireBroadcastsAndNotify() {
    List<RxBroadcast> expired = broadcasts.expireBroadcasts(now());
    for (RxBroadcast b : expired) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("notification_id", UUID.randomUUID().toString());
      payload.put("customer_id", b.customerId().toString());
      payload.put("channel", "PUSH");
      payload.put("title", "Rx quote expired");
      payload.put(
          "body", "Your prescription quote broadcast expired. Re-broadcast or try another option.");
      payload.put("broadcast_id", b.id().toString());
      outbox.publish(
          DomainEvent.of("customer.notification.requested", "customer", b.customerId(), payload));
    }
    return expired.size();
  }

  private Map<String, Object> broadcastCreatedView(
      RxBroadcast broadcast, List<RxBroadcastPharmacy> slots) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("broadcast_id", broadcast.id());
    data.put("status", broadcast.status().name());
    data.put("pharmacies_notified", broadcast.pharmaciesNotified());
    data.put("broadcast_at", broadcast.broadcastAt().toString());
    data.put("expires_at", broadcast.expiresAt().toString());
    List<Map<String, Object>> pharmacyRows = new ArrayList<>();
    for (RxBroadcastPharmacy slot : slots) {
      PharmacyRow p = this.pharmacies.findById(slot.pharmacyId()).orElse(null);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("pharmacy_id", slot.pharmacyId());
      row.put("name", p == null ? null : p.name());
      row.put("area", p == null ? null : p.area());
      row.put("distance_km", roundKm(slot.distanceKm()));
      row.put("status", slot.status().name());
      pharmacyRows.add(row);
    }
    data.put("pharmacies", pharmacyRows);
    data.put("can_view_quotes", false);
    data.put("quotes_received", 0);
    return data;
  }

  private List<Map<String, Object>> pharmacyStatusList(
      List<RxBroadcastPharmacy> slots, boolean canView) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (RxBroadcastPharmacy slot : slots) {
      PharmacyRow p = pharmacies.findById(slot.pharmacyId()).orElse(null);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("pharmacy_id", slot.pharmacyId());
      row.put("name", p == null ? null : p.name());
      row.put("area", p == null ? null : p.area());
      row.put("distance_km", roundKm(slot.distanceKm()));
      row.put("status", slot.status().name());
      if (canView && slot.status() == RxPharmacySlotStatus.QUOTED) {
        Map<String, Object> quote = new LinkedHashMap<>();
        int covered = slot.medicinesAvailable() == null ? 0 : slot.medicinesAvailable().size();
        quote.put("medicines_covered", covered);
        quote.put(
            "total_payable",
            CartPricing.paiseToRupees(
                slot.totalPayablePaise() == null ? 0L : slot.totalPayablePaise()));
        quote.put("eta_minutes", slot.deliveryEtaMinutes());
        quote.put(
            "expires_at", slot.quoteExpiresAt() == null ? null : slot.quoteExpiresAt().toString());
        row.put("quote", quote);
      } else {
        row.put("quote", null);
      }
      out.add(row);
    }
    return out;
  }

  private List<Map<String, Object>> quoteViews(
      RxBroadcast broadcast, List<RxBroadcastPharmacy> slots) {
    Instant now = now();
    List<RxBroadcastPharmacy> quoted =
        slots.stream().filter(s -> s.status() == RxPharmacySlotStatus.QUOTED).toList();
    List<TaggedQuote> tagged = new ArrayList<>();
    for (RxBroadcastPharmacy s : quoted) {
      tagged.add(
          new TaggedQuote(
              s.pharmacyId(),
              s.deliveryEtaMinutes() == null ? Integer.MAX_VALUE : s.deliveryEtaMinutes(),
              s.totalPayablePaise() == null ? Long.MAX_VALUE : s.totalPayablePaise(),
              s.quoteExpired(now)));
    }
    Map<UUID, List<String>> tags = RxQuoteTags.assign(tagged);
    int requestedTotal = broadcast.medicinesRequested().size();
    List<Map<String, Object>> out = new ArrayList<>();
    for (RxBroadcastPharmacy s : quoted) {
      PharmacyRow p = pharmacies.findById(s.pharmacyId()).orElse(null);
      QuoteBill bill =
          s.medicinesAvailable() == null
              ? new QuoteBill(0, 0, 0, 0)
              : RxQuotePricing.compute(s.medicinesAvailable());
      boolean expired = s.quoteExpired(now);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("pharmacy_id", s.pharmacyId());
      row.put("pharmacy_name", p == null ? null : p.name());
      row.put("pharmacy_logo", p == null ? null : p.logoUrl());
      row.put("rating", p == null ? null : SmartPharmacySelectionService.round(p.rating(), 1));
      row.put("distance_km", roundKm(s.distanceKm()));
      row.put("eta_minutes", s.deliveryEtaMinutes());
      row.put("tags", tags.getOrDefault(s.pharmacyId(), List.of()));
      row.put(
          "medicines_covered", s.medicinesAvailable() == null ? 0 : s.medicinesAvailable().size());
      row.put("medicines_total_requested", requestedTotal);
      row.put("medicines", quotedItemsView(s.medicinesAvailable()));
      row.put("total_payable", CartPricing.paiseToRupees(bill.totalPayablePaise()));
      row.put("delivery_fee", CartPricing.paiseToRupees(bill.deliveryFeePaise()));
      row.put("handling_fee", CartPricing.paiseToRupees(bill.handlingFeePaise()));
      row.put("grand_total", CartPricing.paiseToRupees(bill.totalPayablePaise()));
      row.put("quoted_at", s.quotedAt() == null ? null : s.quotedAt().toString());
      row.put("expires_at", s.quoteExpiresAt() == null ? null : s.quoteExpiresAt().toString());
      row.put("is_expired", expired);
      out.add(row);
    }
    return out;
  }

  private static List<Map<String, Object>> quotedItemsView(List<QuotedMedicine> meds) {
    if (meds == null) {
      return List.of();
    }
    List<Map<String, Object>> out = new ArrayList<>();
    for (QuotedMedicine m : meds) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("name", m.name());
      row.put("quantity", m.quantity());
      row.put("price", CartPricing.paiseToRupees(m.pricePaise()));
      out.add(row);
    }
    return out;
  }

  private static List<CartItem> toCartItems(List<QuotedMedicine> meds) {
    List<CartItem> items = new ArrayList<>();
    for (QuotedMedicine m : meds) {
      if (m.productId() == null) {
        throw new AppException("VALIDATION_ERROR", "Each quoted medicine needs product_id", 400);
      }
      long unit = m.pricePaise() / m.quantity();
      items.add(
          new CartItem(
              UUID.randomUUID(),
              m.productId(),
              m.quantity(),
              unit,
              true,
              m.name(),
              null,
              null,
              null));
    }
    return items;
  }

  private static List<QuotedMedicine> parseQuotedMedicines(List<Map<String, Object>> raw) {
    List<QuotedMedicine> out = new ArrayList<>();
    for (Map<String, Object> m : raw) {
      if (m == null) {
        continue;
      }
      Object nameObj = m.get("name");
      Object qtyObj = m.containsKey("qty") ? m.get("qty") : m.get("quantity");
      Object priceObj = m.get("price");
      Object productObj = m.containsKey("product_id") ? m.get("product_id") : m.get("medicine_id");
      if (nameObj == null || qtyObj == null || priceObj == null) {
        throw new AppException("VALIDATION_ERROR", "Each medicine needs name, qty, and price", 400);
      }
      int qty = ((Number) qtyObj).intValue();
      long paise = RxQuotePricing.rupeesToPaise(priceObj);
      UUID productId = null;
      if (productObj != null && !String.valueOf(productObj).isBlank()) {
        try {
          productId = UUID.fromString(String.valueOf(productObj));
        } catch (IllegalArgumentException e) {
          throw new AppException("VALIDATION_ERROR", "product_id must be a UUID", 400);
        }
      }
      out.add(new QuotedMedicine(String.valueOf(nameObj), qty, paise, productId));
    }
    if (out.isEmpty()) {
      throw new AppException("VALIDATION_ERROR", "medicines_available is required", 400);
    }
    return out;
  }

  private static List<RequestedMedicine> toRequested(List<MedicineLine> lines) {
    List<RequestedMedicine> out = new ArrayList<>();
    for (MedicineLine line : lines) {
      out.add(new RequestedMedicine(line.name(), line.quantity()));
    }
    return out;
  }

  private static List<String> requestedLabels(List<RequestedMedicine> meds) {
    List<String> out = new ArrayList<>();
    for (RequestedMedicine m : meds) {
      out.add(m.name() + " (" + m.quantity() + ")");
    }
    return out;
  }

  private static int countQuoted(List<RxBroadcastPharmacy> slots) {
    int n = 0;
    for (RxBroadcastPharmacy s : slots) {
      if (s.status() == RxPharmacySlotStatus.QUOTED) {
        n++;
      }
    }
    return n;
  }

  private RxBroadcast requireCustomerBroadcast(UUID broadcastId, UUID customerId) {
    if (broadcastId == null) {
      throw new AppException("BROADCAST_NOT_FOUND", "Broadcast not found", 404);
    }
    return broadcasts
        .findByIdForCustomer(broadcastId, customerId)
        .orElseThrow(() -> new AppException("BROADCAST_NOT_FOUND", "Broadcast not found", 404));
  }

  private Instant now() {
    return clock.instant();
  }

  private static double roundKm(double km) {
    return BigDecimal.valueOf(km).setScale(1, RoundingMode.HALF_UP).doubleValue();
  }

  static void requireCustomer(MedmatePrincipal principal) {
    if (principal == null || principal.role() != AuthRole.CUSTOMER) {
      throw new AppException("UNAUTHORIZED", "Customer authentication required", 401);
    }
  }

  static void requirePharmacy(MedmatePrincipal principal) {
    if (principal == null
        || (principal.role() != AuthRole.PHARMACY_OWNER
            && principal.role() != AuthRole.PHARMACY_STAFF)
        || principal.pharmacyId() == null) {
      throw new AppException("UNAUTHORIZED", "Pharmacy authentication required", 401);
    }
  }

  private void rateLimit(String key, int limit, int windowSeconds) {
    if (!rateLimiter.tryAcquire(key, limit, windowSeconds)) {
      int retry = rateLimiter.secondsUntilAvailable(key, limit, windowSeconds);
      throw new AppException("RATE_LIMITED", "Too many requests", 429, Math.max(retry, 1));
    }
  }

  private record Ranked(PharmacyRow row, double distanceKm) {}
}
