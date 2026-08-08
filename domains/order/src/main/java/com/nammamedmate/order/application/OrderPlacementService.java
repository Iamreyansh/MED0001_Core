package com.nammamedmate.order.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.order.application.port.out.CartStore;
import com.nammamedmate.order.application.port.out.CustomerAddressPort;
import com.nammamedmate.order.application.port.out.CustomerAddressPort.AddressRow;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort.StockLine;
import com.nammamedmate.order.application.port.out.OrderStatusEventStore;
import com.nammamedmate.order.application.port.out.OrderStore;
import com.nammamedmate.order.application.port.out.PharmacyCandidatePort;
import com.nammamedmate.order.application.port.out.PharmacyCandidatePort.PharmacyRow;
import com.nammamedmate.order.application.port.out.PrescriptionPort;
import com.nammamedmate.order.application.port.out.PriceCeilingPort;
import com.nammamedmate.order.application.port.out.RazorpayPaymentPort;
import com.nammamedmate.order.application.port.out.WalletBalancePort;
import com.nammamedmate.order.application.port.out.WalletPort;
import com.nammamedmate.order.application.port.out.ZoneMembershipPort;
import com.nammamedmate.order.domain.ActorType;
import com.nammamedmate.order.domain.Cart;
import com.nammamedmate.order.domain.CartItem;
import com.nammamedmate.order.domain.CartPricing;
import com.nammamedmate.order.domain.CartPricing.Bill;
import com.nammamedmate.order.domain.CartStatus;
import com.nammamedmate.order.domain.Haversine;
import com.nammamedmate.order.domain.Order;
import com.nammamedmate.order.domain.OrderItemSnapshot;
import com.nammamedmate.order.domain.OrderStatus;
import com.nammamedmate.order.domain.OrderStatusEvent;
import com.nammamedmate.order.domain.PaymentMethod;
import com.nammamedmate.order.domain.PaymentStatus;
import com.nammamedmate.order.domain.PharmacyScorer;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderPlacementService {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final DateTimeFormatter ORDER_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
  private static final Duration ACCEPTANCE_SLA = Duration.ofMinutes(10);

  private final CartService cartService;
  private final CartStore carts;
  private final OrderStore orders;
  private final OrderStatusEventStore statusEvents;
  private final InventoryAvailabilityPort inventory;
  private final PharmacyCandidatePort pharmacies;
  private final CustomerAddressPort addresses;
  private final WalletBalancePort walletBalance;
  private final WalletPort wallet;
  private final PrescriptionPort prescriptions;
  private final ZoneMembershipPort zones;
  private final PriceCeilingPort priceCeiling;
  private final RazorpayPaymentPort razorpay;
  private final RefundService refunds;
  private final OutboxPublisher outbox;
  private final ObjectMapper objectMapper;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public OrderPlacementService(
      CartService cartService,
      CartStore carts,
      OrderStore orders,
      OrderStatusEventStore statusEvents,
      InventoryAvailabilityPort inventory,
      PharmacyCandidatePort pharmacies,
      CustomerAddressPort addresses,
      WalletBalancePort walletBalance,
      WalletPort wallet,
      PrescriptionPort prescriptions,
      ZoneMembershipPort zones,
      PriceCeilingPort priceCeiling,
      RazorpayPaymentPort razorpay,
      RefundService refunds,
      OutboxPublisher outbox,
      ObjectMapper objectMapper,
      RateLimiter rateLimiter,
      Clock clock) {
    this.cartService = cartService;
    this.carts = carts;
    this.orders = orders;
    this.statusEvents = statusEvents;
    this.inventory = inventory;
    this.pharmacies = pharmacies;
    this.addresses = addresses;
    this.walletBalance = walletBalance;
    this.wallet = wallet;
    this.prescriptions = prescriptions;
    this.zones = zones;
    this.priceCeiling = priceCeiling;
    this.razorpay = razorpay;
    this.refunds = refunds;
    this.outbox = outbox;
    this.objectMapper = objectMapper;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  @Transactional
  public Map<String, Object> placeOrder(
      MedmatePrincipal principal,
      UUID cartId,
      String paymentMethodRaw,
      String paymentToken,
      String deliveryInstructions,
      String idempotencyKey) {
    requireCustomer(principal);
    rateLimit("order:place:" + principal.subject(), 5, 60);
    String idem = requireIdempotencyKey(idempotencyKey);

    var existing = orders.findByPlacementIdempotencyKey(idem);
    if (existing.isPresent()) {
      return placeResponse(existing.get(), false);
    }

    if (cartId == null) {
      throw new AppException("VALIDATION_ERROR", "cart_id is required", 400);
    }
    PaymentMethod method = parsePaymentMethod(paymentMethodRaw);
    String instructions = normalizeInstructions(deliveryInstructions);

    Cart cart =
        carts
            .findById(cartId)
            .orElseThrow(() -> new AppException("CART_NOT_FOUND", "Cart not found", 404));
    if (!cart.customerId().equals(principal.subject())) {
      throw new AppException("CART_NOT_FOUND", "Cart not found", 404);
    }
    if (cart.isEmpty()) {
      throw new AppException("CART_EMPTY", "Cart has no items", 422);
    }
    cartService.assertCheckoutReady(cart);

    if (cart.deliveryAddressId() == null) {
      throw new AppException("ADDRESS_NOT_SET", "Delivery address is required", 422);
    }
    AddressRow address =
        addresses
            .findForCustomer(cart.deliveryAddressId(), cart.customerId())
            .orElseThrow(
                () -> new AppException("ADDRESS_NOT_SET", "Delivery address is required", 422));
    if (cart.pharmacyId() == null) {
      throw new AppException("PHARMACY_OFFLINE", "Pharmacy is not accepting online orders", 422);
    }
    if (!zones.isInPharmacyZone(cart.pharmacyId(), address.lat(), address.lng())) {
      throw new AppException(
          "ADDRESS_OUT_OF_ZONE", "Address outside pharmacy serviceable zone", 422);
    }

    PharmacyRow pharmacy =
        pharmacies
            .findById(cart.pharmacyId())
            .orElseThrow(
                () ->
                    new AppException(
                        "PHARMACY_OFFLINE", "Pharmacy is not accepting online orders", 422));
    if (!pharmacy.isOpen()) {
      throw new AppException("PHARMACY_OFFLINE", "Pharmacy is not accepting online orders", 422);
    }

    assertLiveStock(cart);
    assertPrescription(cart);
    priceCeiling.assertWithinCeiling(
        cart.pharmacyId(),
        cart.items().stream()
            .map(i -> new PriceCeilingPort.Line(i.productId(), i.unitPricePaise()))
            .toList());

    long balance = walletBalance.balancePaise(cart.customerId());
    Bill bill = CartPricing.compute(cart.itemTotalPaise(), cart.couponCode(), balance);
    long beforeWallet =
        bill.subtotalAfterDiscountPaise() + bill.deliveryFeePaise() + bill.handlingFeePaise();

    if (method == PaymentMethod.WALLET && bill.totalPayablePaise() > 0) {
      throw new AppException(
          "VALIDATION_ERROR", "Wallet balance insufficient for full WALLET payment", 422);
    }
    if (paymentToken != null && paymentToken.length() > 512) {
      throw new AppException("VALIDATION_ERROR", "payment_token is too long", 400);
    }

    Instant now = clock.instant();
    UUID orderId = UUID.randomUUID();
    String orderNumber = allocateOrderNumber(now);
    List<OrderItemSnapshot> items =
        cart.items().stream().map(OrderItemSnapshot::fromCartItem).toList();

    Order order =
        new Order(
            orderId,
            orderNumber,
            cart.customerId(),
            cart.pharmacyId(),
            cart.id(),
            items,
            bill.itemTotalPaise(),
            cart.couponCode(),
            bill.couponDiscountPaise(),
            bill.deliveryFeePaise(),
            bill.handlingFeePaise(),
            bill.walletAppliedPaise(),
            bill.totalPayablePaise(),
            method,
            PaymentStatus.AWAITING_PAYMENT,
            null,
            null,
            cart.prescriptionId(),
            cart.deliveryAddressId(),
            instructions,
            OrderStatus.PAYMENT_PENDING,
            null,
            null,
            idem,
            null,
            null,
            now,
            now);

    boolean needsOnlinePay = method.isOnline() && bill.totalPayablePaise() > 0;
    if (needsOnlinePay) {
      try {
        var rz = razorpay.createOrder(orderId, bill.totalPayablePaise());
        order.markPaymentPending(rz.razorpayOrderId(), now);
      } catch (AppException e) {
        if ("PAYMENT_INITIATION_FAILED".equals(e.code())) {
          throw e;
        }
        throw new AppException("PAYMENT_INITIATION_FAILED", "Razorpay order creation failed", 502);
      } catch (RuntimeException e) {
        throw new AppException("PAYMENT_INITIATION_FAILED", "Razorpay order creation failed", 502);
      }
    } else {
      Instant eta = estimatedDeliveryAt(now, pharmacy, address);
      order.confirm(now, eta, null);
    }

    if (bill.walletAppliedPaise() > 0) {
      wallet.debitForOrder(
          cart.customerId(),
          orderId,
          Math.max(beforeWallet, 1L),
          "Payment for order #" + orderNumber);
    }

    orders.insert(order);
    cart.setStatus(CartStatus.CHECKED_OUT);
    cart.touch(now);
    carts.update(cart);

    if (order.status() == OrderStatus.PENDING_ACCEPTANCE) {
      recordPendingAcceptance(order, now);
      notifyPharmacy(order, address, pharmacy);
    }

    return placeResponse(order, order.status() == OrderStatus.PENDING_ACCEPTANCE);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getOrder(MedmatePrincipal principal, UUID orderId) {
    requireCustomer(principal);
    rateLimit("order:get:" + principal.subject(), 60, 60);
    if (orderId == null) {
      throw new AppException("VALIDATION_ERROR", "order_id is required", 400);
    }
    Order order =
        orders
            .findByCustomerAndId(principal.subject(), orderId)
            .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "Order not found", 404));
    return detailView(order);
  }

  @Transactional
  public Map<String, Object> confirmPayment(
      MedmatePrincipal principal,
      UUID orderId,
      String paymentId,
      String paymentSignature,
      String idempotencyKey) {
    requireCustomer(principal);
    rateLimit("order:pay-confirm:" + principal.subject(), 10, 60);
    requireIdempotencyKey(idempotencyKey);
    if (orderId == null) {
      throw new AppException("VALIDATION_ERROR", "order_id is required", 400);
    }
    if (paymentId == null || paymentId.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "payment_id is required", 400);
    }
    if (paymentSignature == null || paymentSignature.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "payment_signature is required", 400);
    }

    Order order =
        orders
            .findByCustomerAndId(principal.subject(), orderId)
            .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "Order not found", 404));

    if (order.paymentStatus() == PaymentStatus.PAID
        && paymentId.equals(order.razorpayPaymentId())) {
      return confirmView(order, true);
    }
    if (order.status() != OrderStatus.PAYMENT_PENDING) {
      throw new AppException("ORDER_NOT_IN_PAYMENT_PENDING", "Order is not awaiting payment", 409);
    }
    if (order.razorpayOrderId() == null
        || !razorpay.verifyPaymentSignature(order.razorpayOrderId(), paymentId, paymentSignature)) {
      throw new AppException("PAYMENT_SIGNATURE_INVALID", "HMAC verification failed", 422);
    }

    confirmOnlineOrder(order, paymentId);
    return confirmView(order, true);
  }

  @Transactional
  public Map<String, Object> collectCod(
      MedmatePrincipal principal, UUID orderId, Object amountCollected) {
    requireRider(principal);
    rateLimit("order:cod-collect:" + principal.subject(), 10, 60);
    if (orderId == null) {
      throw new AppException("VALIDATION_ERROR", "order_id is required", 400);
    }
    Order order =
        orders
            .findById(orderId)
            .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "Order not found", 404));
    if (order.paymentMethod() != PaymentMethod.COD) {
      throw new AppException("VALIDATION_ERROR", "Order is not COD", 422);
    }
    if (order.riderId() == null || !order.riderId().equals(principal.subject())) {
      throw new AppException("FORBIDDEN", "Rider is not assigned to this order", 403);
    }
    if (order.status() != OrderStatus.READY_FOR_PICKUP
        && order.status() != OrderStatus.OUT_FOR_DELIVERY) {
      throw new AppException(
          "VALIDATION_ERROR", "COD collect allowed only when ready or out for delivery", 422);
    }
    if (order.paymentStatus() == PaymentStatus.COLLECTED) {
      return codCollectView(order);
    }
    if (order.paymentStatus() != PaymentStatus.PENDING_COLLECTION) {
      throw new AppException("VALIDATION_ERROR", "COD not pending collection", 422);
    }
    long expected = order.totalPayablePaise();
    long collected = parseAmountPaise(amountCollected);
    if (collected != expected) {
      throw new AppException("VALIDATION_ERROR", "amount_collected must equal total payable", 422);
    }
    Instant now = clock.instant();
    order.markCodCollected(now);
    orders.update(order);
    return codCollectView(order);
  }

  @Transactional
  public Map<String, Object> handleRazorpayWebhook(
      String signatureHeader, byte[] rawBody, String idempotencyKey) {
    // Idempotent on payment_id / refund id in payload; Idempotency-Key unused for webhooks.
    if (!razorpay.verifyWebhookSignature(
        signatureHeader, rawBody == null ? new byte[0] : rawBody)) {
      throw new AppException("PAYMENT_SIGNATURE_INVALID", "Webhook signature invalid", 422);
    }
    try {
      JsonNode root = objectMapper.readTree(rawBody);
      String event = text(root, "event");
      if ("refund.processed".equals(event)) {
        return refunds.handleRefundProcessed(root);
      }
      if (!"payment.captured".equals(event)) {
        return Map.of("ignored", true);
      }
      JsonNode entity = root.path("payload").path("payment").path("entity");
      String paymentId = text(entity, "id");
      String razorpayOrderId = text(entity, "order_id");
      if (paymentId == null) {
        return Map.of("ignored", true);
      }
      if (razorpayOrderId == null) {
        return Map.of("ignored", true);
      }
      Order order =
          orders
              .findByRazorpayOrderId(razorpayOrderId)
              .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "Order not found", 404));
      if (order.paymentStatus() == PaymentStatus.PAID
          && paymentId.equals(order.razorpayPaymentId())) {
        return Map.of(
            "order_id", order.id().toString(),
            "status", order.status().name(),
            "payment_status", order.paymentStatus().name());
      }
      if (order.status() != OrderStatus.PAYMENT_PENDING) {
        return Map.of(
            "order_id", order.id().toString(),
            "status", order.status().name(),
            "ignored", true);
      }
      confirmOnlineOrder(order, paymentId);
      return Map.of(
          "order_id", order.id().toString(),
          "status", order.status().name(),
          "payment_status", order.paymentStatus().name());
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      throw new AppException("VALIDATION_ERROR", "Invalid webhook payload", 400);
    }
  }

  private void confirmOnlineOrder(Order order, String paymentId) {
    Instant now = clock.instant();
    PharmacyRow pharmacy = pharmacies.findById(order.pharmacyId()).orElse(null);
    AddressRow address =
        addresses.findForCustomer(order.deliveryAddressId(), order.customerId()).orElse(null);
    Instant eta = estimatedDeliveryAt(now, pharmacy, address);
    order.confirm(now, eta, paymentId);
    orders.update(order);
    recordPendingAcceptance(order, now);
    notifyPharmacy(order, address, pharmacy);
  }

  private void recordPendingAcceptance(Order order, Instant at) {
    statusEvents.append(
        new OrderStatusEvent(
            UUID.randomUUID(),
            order.id(),
            OrderStatus.PAYMENT_PENDING,
            OrderStatus.PENDING_ACCEPTANCE,
            ActorType.SYSTEM,
            null,
            null,
            at));
  }

  private void assertLiveStock(Cart cart) {
    List<UUID> ids = cart.items().stream().map(CartItem::productId).toList();
    List<StockLine> lines = inventory.checkAvailability(cart.pharmacyId(), ids);
    Map<UUID, StockLine> byId = new LinkedHashMap<>();
    for (StockLine line : lines) {
      byId.put(line.medicineId(), line);
    }
    List<Map<String, Object>> outOfStock = new ArrayList<>();
    for (CartItem item : cart.items()) {
      StockLine stock = byId.get(item.productId());
      if (stock == null) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("medicine_id", item.productId());
        row.put("name", item.name());
        row.put("requested", item.quantity());
        row.put("available", 0);
        outOfStock.add(row);
        continue;
      }
      boolean lowQty = stock.quantityAvailable() < item.quantity();
      if (!stock.inStock() || lowQty) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("medicine_id", item.productId());
        String stockName = stock.name();
        row.put("name", stockName == null ? item.name() : stockName);
        row.put("requested", item.quantity());
        row.put("available", Math.max(stock.quantityAvailable(), 0));
        outOfStock.add(row);
      }
    }
    if (!outOfStock.isEmpty()) {
      throw new AppException(
          "ITEMS_OUT_OF_STOCK",
          "One or more items unavailable",
          422,
          null,
          Map.of("out_of_stock_items", outOfStock));
    }
  }

  private void assertPrescription(Cart cart) {
    if (!cart.hasRxItem()) {
      return;
    }
    if (cart.prescriptionId() == null) {
      throw new AppException(
          "PRESCRIPTION_REQUIRED", "Prescription required for Rx items in cart", 422);
    }
    var rx =
        prescriptions
            .findVerified(cart.prescriptionId(), cart.customerId())
            .orElseThrow(
                () ->
                    new AppException(
                        "PRESCRIPTION_REQUIRED",
                        "Prescription required for Rx items in cart",
                        422));
    String status = rx.status() == null ? "" : rx.status().toUpperCase(Locale.ROOT);
    if (!List.of("VERIFIED", "UPLOADED").contains(status)) {
      throw new AppException(
          "PRESCRIPTION_REQUIRED", "Prescription required for Rx items in cart", 422);
    }
  }

  private void notifyPharmacy(Order order, AddressRow address, PharmacyRow pharmacy) {
    Instant deadline = clock.instant().plus(ACCEPTANCE_SLA);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("order_id", order.id().toString());
    payload.put("order_number", order.orderNumber());
    payload.put("pharmacy_id", order.pharmacyId().toString());
    payload.put("customer_id", order.customerId().toString());
    payload.put("acceptance_deadline", deadline.toString());
    if (address != null) {
      payload.put("delivery_address", address.fullAddress());
    }
    List<Map<String, Object>> itemList = new ArrayList<>();
    for (OrderItemSnapshot item : order.items()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("name", item.name());
      row.put("quantity", item.quantity());
      itemList.add(row);
    }
    payload.put("items", itemList);
    payload.put("channel", "WHATSAPP");
    outbox.publish(DomainEvent.of("order.placed.pharmacy_notified", "order", order.id(), payload));

    Map<String, Object> push = new LinkedHashMap<>(payload);
    push.put("channel", "PUSH");
    push.put("title", "New order " + order.orderNumber());
    push.put(
        "body",
        "Accept within 10 minutes — "
            + order.items().size()
            + " item(s)"
            + (pharmacy == null ? "" : " at " + pharmacy.name()));
    outbox.publish(DomainEvent.of("customer.notification.requested", "order", order.id(), push));
  }

  private String allocateOrderNumber(Instant now) {
    LocalDate dateIst = now.atZone(IST).toLocalDate();
    int seq = orders.nextSequence(dateIst);
    return "ORD-" + ORDER_DATE.format(dateIst) + "-" + String.format("%05d", seq);
  }

  private Instant estimatedDeliveryAt(
      Instant confirmedAt, PharmacyRow pharmacy, AddressRow address) {
    double dist = distanceKm(pharmacy, address);
    Double prep = pharmacy == null ? null : pharmacy.avgPrepMinutes();
    int etaMin = PharmacyScorer.deliveryEtaMinutes(dist, prep);
    return confirmedAt.plus(Duration.ofMinutes(etaMin));
  }

  static double distanceKm(PharmacyRow pharmacy, AddressRow address) {
    if (pharmacy == null) {
      return 0;
    }
    if (address == null) {
      return 0;
    }
    Double lat = pharmacy.latitude();
    if (lat == null) {
      return 0;
    }
    Double lng = pharmacy.longitude();
    if (lng == null) {
      return 0;
    }
    return Haversine.distanceKm(address.lat(), address.lng(), lat, lng);
  }

  private Map<String, Object> placeResponse(Order order, boolean pharmacyNotified) {
    if (order.status() == OrderStatus.PAYMENT_PENDING) {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("order_id", order.id());
      data.put("order_number", order.orderNumber());
      data.put("status", order.status().name());
      Map<String, Object> payment = new LinkedHashMap<>();
      payment.put("method", order.paymentMethod().name());
      payment.put("status", order.paymentStatus().name());
      payment.put("razorpay_order_id", order.razorpayOrderId());
      payment.put("amount_paise", order.totalPayablePaise());
      data.put("payment", payment);
      data.put("created_at", order.createdAt().toString());
      return data;
    }
    Map<String, Object> data = confirmedPlaceView(order);
    data.put("pharmacy_notified", pharmacyNotified);
    return data;
  }

  private Map<String, Object> confirmedPlaceView(Order order) {
    PharmacyRow pharmacy = pharmacies.findById(order.pharmacyId()).orElse(null);
    AddressRow address =
        addresses.findForCustomer(order.deliveryAddressId(), order.customerId()).orElse(null);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("order_id", order.id());
    data.put("order_number", order.orderNumber());
    data.put("status", order.status().name());
    data.put("pharmacy", pharmacyBrief(pharmacy, order.pharmacyId()));
    data.put("items", itemsPlaceView(order));
    data.put("bill", billView(order));
    data.put("payment", paymentView(order));
    Map<String, Object> addr = new LinkedHashMap<>();
    addr.put("full_address", address == null ? null : address.fullAddress());
    data.put("delivery_address", addr);
    data.put("estimated_delivery_at", iso(order.estimatedDeliveryAt()));
    data.put("created_at", order.createdAt().toString());
    return data;
  }

  private Map<String, Object> detailView(Order order) {
    PharmacyRow pharmacy = pharmacies.findById(order.pharmacyId()).orElse(null);
    AddressRow address =
        addresses.findForCustomer(order.deliveryAddressId(), order.customerId()).orElse(null);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", order.id());
    data.put("order_number", order.orderNumber());
    data.put("status", order.status().name());
    Map<String, Object> ph = pharmacyBrief(pharmacy, order.pharmacyId());
    ph.put("phone", orders.findPharmacyPhone(order.pharmacyId()).orElse(null));
    data.put("pharmacy", ph);
    data.put("rider", order.riderId());
    data.put("items", itemsDetailView(order));
    data.put("bill", billView(order));
    data.put("prescription_id", order.prescriptionId());
    Map<String, Object> addr = new LinkedHashMap<>();
    addr.put("full_address", address == null ? null : address.fullAddress());
    data.put("delivery_address", addr);
    data.put("delivery_instructions", order.deliveryInstructions());
    data.put("payment", paymentView(order));
    data.put("estimated_delivery_at", iso(order.estimatedDeliveryAt()));
    data.put("confirmed_at", iso(order.confirmedAt()));
    data.put("created_at", order.createdAt().toString());
    return data;
  }

  private Map<String, Object> confirmView(Order order, boolean pharmacyNotified) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("order_id", order.id());
    data.put("order_number", order.orderNumber());
    data.put("status", order.status().name());
    Map<String, Object> payment = new LinkedHashMap<>();
    payment.put("method", order.paymentMethod().name());
    payment.put("status", order.paymentStatus().name());
    payment.put("transaction_id", order.razorpayPaymentId());
    data.put("payment", payment);
    data.put("pharmacy_notified", pharmacyNotified);
    data.put("confirmed_at", iso(order.confirmedAt()));
    return data;
  }

  private static String iso(Instant instant) {
    return instant == null ? null : instant.toString();
  }

  private Map<String, Object> codCollectView(Order order) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("order_id", order.id());
    data.put("payment_status", order.paymentStatus().name());
    data.put("amount_collected", CartPricing.paiseToRupees(order.totalPayablePaise()));
    data.put("collected_at", order.updatedAt().toString());
    return data;
  }

  private static Map<String, Object> pharmacyBrief(PharmacyRow pharmacy, UUID pharmacyId) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", pharmacy == null ? pharmacyId : pharmacy.id());
    m.put("name", pharmacy == null ? null : pharmacy.name());
    m.put("area", pharmacy == null ? null : pharmacy.area());
    return m;
  }

  private static List<Map<String, Object>> itemsPlaceView(Order order) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (OrderItemSnapshot item : order.items()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("name", item.name());
      row.put("quantity", item.quantity());
      row.put("price", CartPricing.paiseToRupees(item.unitPricePaise()));
      row.put("line_total", CartPricing.paiseToRupees(item.lineTotalPaise()));
      out.add(row);
    }
    return out;
  }

  private static List<Map<String, Object>> itemsDetailView(Order order) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (OrderItemSnapshot item : order.items()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("name", item.name());
      row.put("quantity", item.quantity());
      row.put("unit_price", CartPricing.paiseToRupees(item.unitPricePaise()));
      row.put("line_total", CartPricing.paiseToRupees(item.lineTotalPaise()));
      out.add(row);
    }
    return out;
  }

  private static Map<String, Object> billView(Order order) {
    long subtotal = order.itemTotalPaise() - order.couponDiscountPaise();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("item_total", CartPricing.paiseToRupees(order.itemTotalPaise()));
    m.put("coupon_discount", CartPricing.paiseToRupees(order.couponDiscountPaise()));
    m.put("subtotal_after_discount", CartPricing.paiseToRupees(subtotal));
    m.put("delivery_fee", CartPricing.paiseToRupees(order.deliveryFeePaise()));
    m.put("handling_fee", CartPricing.paiseToRupees(order.handlingFeePaise()));
    m.put("wallet_applied", CartPricing.paiseToRupees(order.walletAppliedPaise()));
    m.put("total_payable", CartPricing.paiseToRupees(order.totalPayablePaise()));
    return m;
  }

  private static Map<String, Object> paymentView(Order order) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("method", order.paymentMethod().name());
    m.put("status", order.paymentStatus().name());
    m.put("transaction_id", order.razorpayPaymentId());
    m.put("razorpay_order_id", order.razorpayOrderId());
    return m;
  }

  static PaymentMethod parsePaymentMethod(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "payment_method is required", 400);
    }
    try {
      return PaymentMethod.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new AppException("VALIDATION_ERROR", "payment_method must be UPI|CARD|COD|WALLET", 400);
    }
  }

  static String requireIdempotencyKey(String key) {
    if (key == null || key.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "Idempotency-Key is required", 400);
    }
    String trimmed = key.trim();
    if (trimmed.length() > 128) {
      throw new AppException(
          "VALIDATION_ERROR", "Idempotency-Key must be at most 128 characters", 400);
    }
    return trimmed;
  }

  static String normalizeInstructions(String instructions) {
    if (instructions == null || instructions.isBlank()) {
      return null;
    }
    String t = instructions.trim();
    if (t.length() > 200) {
      throw new AppException(
          "VALIDATION_ERROR", "delivery_instructions must be at most 200 characters", 400);
    }
    return t;
  }

  static long parseAmountPaise(Object amount) {
    if (amount == null) {
      throw new AppException("VALIDATION_ERROR", "amount_collected is required", 400);
    }
    try {
      BigDecimal bd;
      if (amount instanceof BigDecimal b) {
        bd = b;
      } else if (amount instanceof Number n) {
        bd = BigDecimal.valueOf(n.doubleValue());
      } else {
        bd = new BigDecimal(String.valueOf(amount).trim());
      }
      return bd.movePointRight(2).setScale(0, java.math.RoundingMode.UNNECESSARY).longValueExact();
    } catch (RuntimeException e) {
      throw new AppException("VALIDATION_ERROR", "amount_collected is invalid", 400);
    }
  }

  static void requireCustomer(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Customer authentication required", 401);
    }
    if (principal.role() != AuthRole.CUSTOMER) {
      throw new AppException("UNAUTHORIZED", "Customer authentication required", 401);
    }
  }

  static void requireRider(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Rider authentication required", 401);
    }
    if (principal.role() != AuthRole.RIDER) {
      throw new AppException("UNAUTHORIZED", "Rider authentication required", 401);
    }
  }

  static String text(JsonNode node, String field) {
    if (node == null) {
      return null;
    }
    JsonNode v = node.path(field);
    if (v.isMissingNode()) {
      return null;
    }
    if (v.isNull()) {
      return null;
    }
    String s = v.asText("");
    return s.isBlank() ? null : s;
  }

  private void rateLimit(String key, int limit, int windowSeconds) {
    if (!rateLimiter.tryAcquire(key, limit, windowSeconds)) {
      int retry = rateLimiter.secondsUntilAvailable(key, limit, windowSeconds);
      throw new AppException("RATE_LIMITED", "Too many requests", 429, Math.max(retry, 1));
    }
  }

  /** Exposed for webhook body encoding helpers in tests. */
  static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }
}
