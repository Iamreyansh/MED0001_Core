package com.nammamedmate.pos.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.pos.application.port.out.InvoiceStore;
import com.nammamedmate.pos.application.port.out.OfferStore;
import com.nammamedmate.pos.application.port.out.PosCartStore;
import com.nammamedmate.pos.application.port.out.PosKhataPort;
import com.nammamedmate.pos.application.port.out.StockDeductionPort;
import com.nammamedmate.pos.domain.Invoice;
import com.nammamedmate.pos.domain.InvoiceChannel;
import com.nammamedmate.pos.domain.InvoiceItem;
import com.nammamedmate.pos.domain.InvoiceStatus;
import com.nammamedmate.pos.domain.MoneyMath;
import com.nammamedmate.pos.domain.OfferRedemption;
import com.nammamedmate.pos.domain.OfferRedemptionChannel;
import com.nammamedmate.pos.domain.PaymentMethod;
import com.nammamedmate.pos.domain.PaymentStatus;
import com.nammamedmate.pos.domain.PosCart;
import com.nammamedmate.pos.domain.PosCartItem;
import com.nammamedmate.pos.domain.PosCartStatus;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PosCheckoutService {

  private final PosCartStore cartStore;
  private final InvoiceStore invoiceStore;
  private final StockDeductionPort stockDeduction;
  private final PosKhataPort khata;
  private final OfferStore offerStore;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public PosCheckoutService(
      PosCartStore cartStore,
      InvoiceStore invoiceStore,
      StockDeductionPort stockDeduction,
      PosKhataPort khata,
      OfferStore offerStore,
      RateLimiter rateLimiter,
      Clock clock) {
    this.cartStore = cartStore;
    this.invoiceStore = invoiceStore;
    this.stockDeduction = stockDeduction;
    this.khata = khata;
    this.offerStore = offerStore;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  @Transactional
  public Map<String, Object> checkout(
      MedmatePrincipal principal,
      UUID cartId,
      String paymentMethod,
      BigDecimal amountPaid,
      String upiReference,
      String prescribingDoctor) {
    return checkout(
        principal, cartId, paymentMethod, amountPaid, upiReference, prescribingDoctor, null);
  }

  @Transactional
  public Map<String, Object> checkout(
      MedmatePrincipal principal,
      UUID cartId,
      String paymentMethod,
      BigDecimal amountPaid,
      String upiReference,
      String prescribingDoctor,
      String idempotencyKey) {
    PosCartService.requireStaff(principal);
    if (!rateLimiter.tryAcquire("pos:cart:checkout:" + principal.pharmacyId(), 30, 60)) {
      throw new AppException("RATE_LIMIT_EXCEEDED", "Too many requests", 429);
    }

    PosCart cart =
        cartStore
            .findById(principal.pharmacyId(), cartId)
            .orElseThrow(() -> new AppException("CART_NOT_FOUND", "Cart not found", 404));

    Instant now = clock.instant();
    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      Optional<UUID> prior =
          cartStore.findInvoiceByCheckoutIdempotency(principal.pharmacyId(), idempotencyKey);
      if (prior.isPresent()) {
        PosCart replay =
            new PosCart(
                cart.id(),
                cart.pharmacyId(),
                cart.staffId(),
                cart.customerId(),
                cart.customerName(),
                cart.customerPhone(),
                cart.prescribingDoctor(),
                cart.discountType(),
                cart.discountValue(),
                cart.discountAmountPaise(),
                cart.subtotalPaise(),
                cart.gstTotalPaise(),
                cart.grandTotalPaise(),
                PosCartStatus.COMPLETED,
                cart.expiresAt(),
                prior.get(),
                cart.appliedOfferId(),
                cart.createdAt(),
                now);
        return replayCompleted(principal.pharmacyId(), replay, now);
      }
    }
    if (cart.status() == PosCartStatus.COMPLETED) {
      if (idempotencyKey != null && !idempotencyKey.isBlank() && cart.invoiceId() != null) {
        return replayCompleted(principal.pharmacyId(), cart, now);
      }
      throw new AppException("CART_ALREADY_COMPLETED", "Cart already checked out", 409);
    }
    if (cart.status() == PosCartStatus.ABANDONED) {
      throw new AppException("CART_EXPIRED", "Cart has expired", 400);
    }
    if (cart.expiresAt().isBefore(now)) {
      cartStore.update(
          new PosCart(
              cart.id(),
              cart.pharmacyId(),
              cart.staffId(),
              cart.customerId(),
              cart.customerName(),
              cart.customerPhone(),
              cart.prescribingDoctor(),
              cart.discountType(),
              cart.discountValue(),
              cart.discountAmountPaise(),
              cart.subtotalPaise(),
              cart.gstTotalPaise(),
              cart.grandTotalPaise(),
              PosCartStatus.ABANDONED,
              cart.expiresAt(),
              cart.invoiceId(),
              cart.appliedOfferId(),
              cart.createdAt(),
              now));
      throw new AppException("CART_EXPIRED", "Cart has expired", 400);
    }

    List<PosCartItem> items = cartStore.listItems(cartId);
    if (items.isEmpty()) {
      throw new AppException("EMPTY_CART", "Cart has no items", 400);
    }

    PaymentMethod method = parsePayment(paymentMethod);
    if (method == PaymentMethod.UPI && (upiReference == null || upiReference.isBlank())) {
      throw new AppException("VALIDATION_ERROR", "upi_reference required for UPI", 400);
    }
    if (method == PaymentMethod.CREDIT && cart.customerId() == null) {
      throw new AppException(
          "CREDIT_REQUIRES_NAMED_CUSTOMER", "CREDIT requires a named customer", 400);
    }

    long subtotal = items.stream().mapToLong(PosCartItem::lineTotalPaise).sum();
    long gstTotal;
    long discount;
    if (cart.appliedOfferId() != null) {
      discount = Math.min(cart.discountAmountPaise(), subtotal);
    } else {
      String dtype = null;
      if (cart.discountType() != null) {
        dtype = cart.discountType().name();
      }
      BigDecimal dval = BigDecimal.ZERO;
      if (cart.discountValue() != null) {
        dval = cart.discountValue();
      }
      discount =
          Math.min(
              MoneyMath.computeDiscountAmountPaise(dtype, dval, subtotal),
              MoneyMath.maxDiscountPaise(subtotal));
    }
    long grand = Math.max(0L, subtotal - discount);
    List<long[]> discountedLines = proRateDiscountedLines(items, subtotal, discount);
    gstTotal = 0L;
    for (long[] line : discountedLines) {
      gstTotal += line[1];
    }

    if (method == PaymentMethod.CREDIT) {
      long outstanding = khata.outstandingPaise(principal.pharmacyId(), cart.customerId());
      long limit = khata.creditLimitPaise(principal.pharmacyId(), cart.customerId());
      if (outstanding + grand > limit) {
        throw new AppException(
            "CREDIT_LIMIT_EXCEEDED", "Purchase would exceed customer credit limit", 400);
      }
    }

    boolean rxPresent = items.stream().anyMatch(PosCartItem::isRxOnly);
    String doctor = firstNonBlank(prescribingDoctor, cart.prescribingDoctor());
    if (rxPresent) {
      if (doctor == null) {
        throw new AppException(
            "RX_PRESCRIBER_REQUIRED", "prescribing_doctor required for Rx items", 400);
      }
      if (doctor.isBlank()) {
        throw new AppException(
            "RX_PRESCRIBER_REQUIRED", "prescribing_doctor required for Rx items", 400);
      }
    }
    if (doctor != null) {
      cartStore.setPrescribingDoctor(cartId, doctor, now, cart.expiresAt());
    }

    long paidPaise = amountPaid == null ? 0L : MoneyMath.rupeesToPaise(amountPaid);
    long changeDue = Math.max(0L, paidPaise - grand);

    PaymentStatus payStatus =
        method == PaymentMethod.COD || method == PaymentMethod.CREDIT
            ? PaymentStatus.PENDING
            : PaymentStatus.PAID;

    // Deduct stock first (race → INSUFFICIENT_STOCK, cart stays ACTIVE)
    for (PosCartItem item : items) {
      stockDeduction.deductSale(
          principal.pharmacyId(),
          item.productId(),
          item.batchId(),
          item.quantity(),
          principal.subject(),
          now);
    }

    var settings = invoiceStore.getOrCreateSettings(principal.pharmacyId());
    ZonedDateTime zdt = now.atZone(ZoneOffset.UTC);
    int year = zdt.getYear();
    int month = zdt.getMonthValue();
    int seq = invoiceStore.nextSequence(principal.pharmacyId(), year, month);
    String invoiceNumber =
        String.format(Locale.ROOT, "%s-%04d-%02d-%06d", settings.invoicePrefix(), year, month, seq);

    UUID invoiceId = Ids.newId();
    String pdfUrl =
        "https://cdn.medmate.in/pharmacy/" + principal.pharmacyId() + "/" + invoiceNumber + ".pdf";

    Invoice invoice =
        new Invoice(
            invoiceId,
            principal.pharmacyId(),
            invoiceNumber,
            cartId,
            InvoiceChannel.COUNTER,
            cart.customerId(),
            cart.customerName(),
            cart.customerPhone(),
            doctor,
            subtotal,
            discount,
            gstTotal,
            grand,
            method,
            payStatus,
            upiReference,
            paidPaise,
            changeDue,
            Math.max(0L, subtotal - grand),
            InvoiceStatus.ACTIVE,
            pdfUrl,
            now);
    invoiceStore.insert(invoice);

    List<InvoiceItem> invoiceItems = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      PosCartItem item = items.get(i);
      long discountedLine = discountedLines.get(i)[0];
      long lineGst = discountedLines.get(i)[1];
      invoiceItems.add(
          new InvoiceItem(
              Ids.newId(),
              invoiceId,
              item.productId(),
              item.productName(),
              item.hsnCode(),
              item.batchId(),
              item.batchNumber(),
              item.expiryDate(),
              item.packSize(),
              item.quantity(),
              item.isLoose(),
              item.unitPricePaise(),
              item.gstPct(),
              discountedLine,
              lineGst,
              discountedLine,
              item.isRxOnly(),
              now));
    }
    invoiceStore.insertItems(invoiceItems);
    cartStore.markCompleted(cartId, invoiceId, now);
    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      cartStore.saveCheckoutIdempotency(
          principal.pharmacyId(), idempotencyKey, cartId, invoiceId, now);
    }

    if (cart.appliedOfferId() != null) {
      if (discount > 0) {
        offerStore.insertRedemption(
            new OfferRedemption(
                Ids.newId(),
                cart.appliedOfferId(),
                principal.pharmacyId(),
                invoiceId,
                cart.customerId(),
                discount,
                OfferRedemptionChannel.COUNTER,
                now));
        offerStore.incrementRedemptions(cart.appliedOfferId(), now);
      }
    }

    if (method == PaymentMethod.CREDIT) {
      khata.postCreditSale(cart.customerId(), invoiceId, grand, principal.pharmacyId());
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("invoice_id", invoiceId.toString());
    data.put("invoice_number", invoiceNumber);
    data.put("cart_id", cartId.toString());
    data.put("payment_method", method.name());
    data.put("amount_paid", MoneyMath.paiseToRupees(paidPaise));
    data.put("change_due", MoneyMath.paiseToRupees(changeDue));
    data.put("grand_total", MoneyMath.paiseToRupees(grand));
    data.put("gst_breakdown", gstBreakdown(items, discountedLines));
    data.put("invoice_pdf_url", pdfUrl);
    data.put("items_count", items.size());
    data.put("customer_name", cart.customerName());
    data.put("completed_at", now.toString());
    return data;
  }

  private Map<String, Object> replayCompleted(UUID pharmacyId, PosCart cart, Instant now) {
    Invoice invoice =
        invoiceStore
            .findById(pharmacyId, cart.invoiceId())
            .orElseThrow(
                () -> new AppException("INVOICE_NOT_FOUND", "Completed cart invoice missing", 404));
    List<InvoiceItem> invoiceItems = invoiceStore.listItems(invoice.id());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("invoice_id", invoice.id().toString());
    data.put("invoice_number", invoice.invoiceNumber());
    data.put("cart_id", cart.id().toString());
    data.put("payment_method", invoice.paymentMethod().name());
    data.put("amount_paid", MoneyMath.paiseToRupees(invoice.amountPaidPaise()));
    data.put("change_due", MoneyMath.paiseToRupees(invoice.changeDuePaise()));
    data.put("grand_total", MoneyMath.paiseToRupees(invoice.grandTotalPaise()));
    data.put("invoice_pdf_url", invoice.invoicePdfUrl());
    data.put("items_count", invoiceItems.size());
    data.put("customer_name", cart.customerName());
    data.put("completed_at", invoice.createdAt().toString());
    data.put("idempotent_replay", true);
    return data;
  }

  /** Per line: [discountedInclusive, gst, taxable]. */
  private static List<long[]> proRateDiscountedLines(
      List<PosCartItem> items, long subtotal, long discount) {
    List<long[]> out = new ArrayList<>();
    long allocated = 0L;
    for (int i = 0; i < items.size(); i++) {
      PosCartItem item = items.get(i);
      long lineDiscount;
      if (i == items.size() - 1) {
        lineDiscount = Math.max(0L, discount - allocated);
      } else if (discount <= 0) {
        lineDiscount = 0L;
      } else {
        lineDiscount =
            java.math.BigDecimal.valueOf(discount)
                .multiply(java.math.BigDecimal.valueOf(item.lineTotalPaise()))
                .divide(java.math.BigDecimal.valueOf(subtotal), 0, java.math.RoundingMode.HALF_UP)
                .longValueExact();
        allocated += lineDiscount;
      }
      long discounted = Math.max(0L, item.lineTotalPaise() - lineDiscount);
      long gst = MoneyMath.gstFromInclusive(discounted, item.gstPct());
      out.add(new long[] {discounted, gst, discounted - gst});
    }
    return out;
  }

  private static List<Map<String, Object>> gstBreakdown(
      List<PosCartItem> items, List<long[]> discountedLines) {
    Map<Integer, long[]> bySlab = new TreeMap<>();
    for (int i = 0; i < items.size(); i++) {
      PosCartItem item = items.get(i);
      long[] priced = discountedLines.get(i);
      long[] agg = bySlab.computeIfAbsent(item.gstPct(), k -> new long[2]);
      agg[0] += priced[2];
      agg[1] += priced[1];
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Map.Entry<Integer, long[]> e : bySlab.entrySet()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("slab", e.getKey() + "%");
      row.put("taxable_amount", MoneyMath.paiseToRupees(e.getValue()[0]));
      row.put("gst_amount", MoneyMath.paiseToRupees(e.getValue()[1]));
      rows.add(row);
    }
    return rows;
  }

  private static String firstNonBlank(String primary, String fallback) {
    if (primary != null && !primary.isBlank()) {
      return primary.trim();
    }
    return fallback;
  }

  private static PaymentMethod parsePayment(String paymentMethod) {
    if (paymentMethod == null || paymentMethod.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "payment_method is required", 400);
    }
    try {
      return PaymentMethod.valueOf(paymentMethod.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new AppException("VALIDATION_ERROR", "Invalid payment_method", 400);
    }
  }
}
