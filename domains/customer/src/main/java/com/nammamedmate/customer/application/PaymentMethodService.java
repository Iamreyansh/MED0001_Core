package com.nammamedmate.customer.application;

import com.nammamedmate.customer.application.port.out.CustomerProfileStore;
import com.nammamedmate.customer.application.port.out.PaymentMethodInActiveOrderPort;
import com.nammamedmate.customer.application.port.out.PaymentMethodStore;
import com.nammamedmate.customer.application.port.out.PaymentMethodStore.PaymentMethodRecord;
import com.nammamedmate.customer.application.port.out.RazorpayVpaPort;
import com.nammamedmate.customer.domain.CardNetwork;
import com.nammamedmate.customer.domain.CardType;
import com.nammamedmate.customer.domain.PaymentMethodType;
import com.nammamedmate.customer.domain.UpiVpa;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AesGcmCipher;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentMethodService {

  private static final int MAX_PER_TYPE = 5;
  private static final int LIST_LIMIT = 30;
  private static final int MUTATE_LIMIT = 10;
  private static final int MINUTE = 60;
  private static final Pattern TOKEN_ID = Pattern.compile("^token_[A-Za-z0-9]+$");
  private static final Pattern LAST4 = Pattern.compile("^\\d{4}$");

  private final PaymentMethodStore methods;
  private final CustomerProfileStore profiles;
  private final PaymentMethodInActiveOrderPort activeOrders;
  private final RazorpayVpaPort vpaPort;
  private final AesGcmCipher cipher;
  private final RateLimiter rateLimiter;
  private final Clock clock;
  private final TransactionTemplate tx;

  public PaymentMethodService(
      PaymentMethodStore methods,
      CustomerProfileStore profiles,
      PaymentMethodInActiveOrderPort activeOrders,
      RazorpayVpaPort vpaPort,
      @Qualifier("paymentMethodCipher") AesGcmCipher cipher,
      RateLimiter rateLimiter,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.methods = methods;
    this.profiles = profiles;
    this.activeOrders = activeOrders;
    this.vpaPort = vpaPort;
    this.cipher = cipher;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.tx = new TransactionTemplate(transactionManager);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> list(MedmatePrincipal principal) {
    UUID customerId = requireCustomerId(principal);
    rateLimit("customer:payment-methods:list:" + customerId, LIST_LIMIT, MINUTE);
    return listViews(customerId);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> listForAdmin(MedmatePrincipal principal, UUID customerId) {
    requireAdminRead(principal);
    profiles
        .findById(customerId)
        .filter(c -> c.deletedAt() == null)
        .orElseThrow(() -> new AppException("CUSTOMER_NOT_FOUND", "Customer not found", 404));
    rateLimit("admin:payment-methods:list:" + principal.subject(), LIST_LIMIT, MINUTE);
    return listViews(customerId);
  }

  /** VPA validation runs outside the DB transaction; insert is short-lived. */
  public Map<String, Object> saveUpi(
      MedmatePrincipal principal, UpiCommand cmd, String idempotencyKey) {
    UUID customerId = requireCustomerId(principal);
    rateLimit("customer:payment-methods:upi:" + customerId, MUTATE_LIMIT, MINUTE);

    String idem = optionalIdempotencyKey(idempotencyKey);
    Optional<Map<String, Object>> replay = replayIfPresent(idem);
    if (replay.isPresent()) {
      return replay.get();
    }

    if (cmd == null) {
      throw new AppException("VALIDATION_ERROR", "Request body is required", 400);
    }
    String vpa = UpiVpa.requireValid(cmd.upiId());
    String nickname = optionalNickname(cmd.nickname());

    if (methods.countByCustomerAndType(customerId, PaymentMethodType.UPI.name()) >= MAX_PER_TYPE) {
      throw new AppException(
          "PAYMENT_METHOD_LIMIT_REACHED", "Maximum of 5 UPI IDs already saved", 422);
    }

    for (PaymentMethodRecord existing :
        methods.listByCustomerAndType(customerId, PaymentMethodType.UPI.name())) {
      if (vpa.equals(cipher.decrypt(existing.upiIdEncrypted()))) {
        throw new AppException("UPI_ALREADY_SAVED", "This UPI ID is already saved", 409);
      }
    }

    // External call — must not hold a DB connection (story: ≤5s).
    if (!vpaPort.validateVpa(vpa)) {
      throw new AppException("INVALID_UPI_VPA", "UPI VPA is invalid", 422);
    }

    Instant now = clock.instant();
    PaymentMethodRecord toSave =
        new PaymentMethodRecord(
            Ids.newId(),
            customerId,
            PaymentMethodType.UPI.name(),
            false,
            nickname,
            cipher.encrypt(vpa),
            UpiVpa.maskHandle(vpa),
            null,
            null,
            null,
            null,
            idem,
            now,
            null);
    tx.executeWithoutResult(status -> methods.insert(toSave));
    return toUpiView(toSave);
  }

  @Transactional
  public Map<String, Object> saveCard(
      MedmatePrincipal principal, CardCommand cmd, String idempotencyKey) {
    UUID customerId = requireCustomerId(principal);
    rateLimit("customer:payment-methods:card:" + customerId, MUTATE_LIMIT, MINUTE);

    String idem = optionalIdempotencyKey(idempotencyKey);
    Optional<Map<String, Object>> replay = replayIfPresent(idem);
    if (replay.isPresent()) {
      return replay.get();
    }

    if (cmd == null) {
      throw new AppException("VALIDATION_ERROR", "Request body is required", 400);
    }
    String tokenId = requireTokenId(cmd.razorpayTokenId());
    String last4 = requireLast4(cmd.cardLast4());
    CardNetwork network = CardNetwork.parse(cmd.cardNetwork());
    CardType cardType = CardType.parse(cmd.cardType());
    String nickname = optionalNickname(cmd.nickname());

    if (methods.countByCustomerAndType(customerId, PaymentMethodType.CARD.name()) >= MAX_PER_TYPE) {
      throw new AppException(
          "PAYMENT_METHOD_LIMIT_REACHED", "Maximum of 5 cards already saved", 422);
    }

    for (PaymentMethodRecord existing :
        methods.listByCustomerAndType(customerId, PaymentMethodType.CARD.name())) {
      if (tokenId.equals(cipher.decrypt(existing.razorpayTokenEncrypted()))) {
        throw new AppException("CARD_ALREADY_SAVED", "This card token is already saved", 409);
      }
    }

    Instant now = clock.instant();
    PaymentMethodRecord saved =
        methods.insert(
            new PaymentMethodRecord(
                Ids.newId(),
                customerId,
                PaymentMethodType.CARD.name(),
                false,
                nickname,
                null,
                null,
                cipher.encrypt(tokenId),
                last4,
                network.name(),
                cardType.name(),
                idem,
                now,
                null));
    return toCardView(saved);
  }

  @Transactional
  public Map<String, Object> delete(MedmatePrincipal principal, UUID methodId) {
    UUID customerId = requireCustomerId(principal);
    rateLimit("customer:payment-methods:delete:" + customerId, MUTATE_LIMIT, MINUTE);
    requireMethod(methodId, customerId);

    if (activeOrders.isPaymentMethodInActiveOrder(methodId)) {
      throw new AppException(
          "PAYMENT_METHOD_IN_ACTIVE_ORDER",
          "Payment method is used by an order in PENDING, CONFIRMED, PACKED, or OUT_FOR_DELIVERY",
          409);
    }

    methods.softDelete(methodId, customerId, clock.instant());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("message", "Payment method removed successfully.");
    return data;
  }

  @Transactional
  public Map<String, Object> setDefault(MedmatePrincipal principal, UUID methodId) {
    UUID customerId = requireCustomerId(principal);
    rateLimit("customer:payment-methods:set-default:" + customerId, MUTATE_LIMIT, MINUTE);
    PaymentMethodRecord existing = requireMethod(methodId, customerId);

    UUID previous = methods.findDefaultMethodId(customerId).orElse(null);
    if (methodId.equals(previous)) {
      throw new AppException("ALREADY_DEFAULT", "This method is already the default", 409);
    }

    methods.clearDefaultFlags(customerId);
    methods.setDefault(methodId, customerId);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", methodId);
    data.put("type", existing.type());
    data.put("is_default", true);
    data.put("previous_default_id", previous);
    data.put("message", "Default payment method updated.");
    return data;
  }

  private Map<String, Object> listViews(UUID customerId) {
    List<Map<String, Object>> upi = new ArrayList<>();
    List<Map<String, Object>> cards = new ArrayList<>();
    for (PaymentMethodRecord row : methods.listByCustomer(customerId)) {
      if (PaymentMethodType.UPI.name().equals(row.type())) {
        upi.add(toUpiView(row));
      } else {
        cards.add(toCardView(row));
      }
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("upi", upi);
    data.put("cards", cards);
    return data;
  }

  private Optional<Map<String, Object>> replayIfPresent(String idem) {
    if (idem == null) {
      return Optional.empty();
    }
    return methods
        .findByIdempotencyKey(idem)
        .map(
            row ->
                PaymentMethodType.UPI.name().equals(row.type()) ? toUpiView(row) : toCardView(row));
  }

  private PaymentMethodRecord requireMethod(UUID methodId, UUID customerId) {
    return methods
        .findByIdForCustomer(methodId, customerId)
        .orElseThrow(
            () -> new AppException("PAYMENT_METHOD_NOT_FOUND", "Payment method not found", 404));
  }

  private UUID requireCustomerId(MedmatePrincipal principal) {
    if (principal == null || principal.role() != AuthRole.CUSTOMER) {
      throw new AppException("UNAUTHORIZED", "Customer authentication required", 401);
    }
    UUID id = principal.subject();
    profiles
        .findById(id)
        .filter(c -> c.deletedAt() == null)
        .orElseThrow(() -> new AppException("CUSTOMER_NOT_FOUND", "Customer not found", 404));
    return id;
  }

  private static void requireAdminRead(MedmatePrincipal principal) {
    if (principal == null || !principal.role().value().startsWith("admin_")) {
      throw new AppException("UNAUTHORIZED", "Admin authentication required", 401);
    }
  }

  private void rateLimit(String key, int limit, int windowSeconds) {
    if (!rateLimiter.tryAcquire(key, limit, windowSeconds)) {
      int retry = rateLimiter.secondsUntilAvailable(key, limit, windowSeconds);
      throw new AppException("RATE_LIMITED", "Too many requests", 429, Math.max(retry, 1));
    }
  }

  private static String optionalNickname(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String trimmed = raw.trim();
    if (trimmed.length() > 50) {
      throw new AppException("VALIDATION_ERROR", "nickname max length is 50", 400);
    }
    return trimmed;
  }

  private static String optionalIdempotencyKey(String key) {
    if (key == null || key.isBlank()) {
      return null;
    }
    String trimmed = key.trim();
    if (trimmed.length() > 255) {
      throw new AppException(
          "VALIDATION_ERROR", "Idempotency-Key must be at most 255 characters", 400);
    }
    return trimmed;
  }

  private static String requireTokenId(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "razorpay_token_id is required", 400);
    }
    String trimmed = raw.trim();
    if (trimmed.length() > 100) {
      throw new AppException("VALIDATION_ERROR", "razorpay_token_id max length is 100", 400);
    }
    if (!TOKEN_ID.matcher(trimmed).matches()) {
      throw new AppException(
          "INVALID_RAZORPAY_TOKEN", "Razorpay token ID format is unrecognised", 422);
    }
    return trimmed;
  }

  private static String requireLast4(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "card_last4 is required", 400);
    }
    String trimmed = raw.trim();
    if (!LAST4.matcher(trimmed).matches()) {
      throw new AppException("VALIDATION_ERROR", "card_last4 must be exactly 4 digits", 400);
    }
    return trimmed;
  }

  static Map<String, Object> toUpiView(PaymentMethodRecord row) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", row.id());
    data.put("type", PaymentMethodType.UPI.name());
    data.put("nickname", row.nickname());
    data.put("upi_handle", row.upiHandle());
    data.put("is_default", row.isDefault());
    data.put("added_at", row.createdAt());
    return data;
  }

  static Map<String, Object> toCardView(PaymentMethodRecord row) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", row.id());
    data.put("type", PaymentMethodType.CARD.name());
    data.put("card_last4", row.cardLast4());
    data.put("card_network", row.cardNetwork());
    data.put("card_type", row.cardType());
    data.put("nickname", row.nickname());
    data.put("is_default", row.isDefault());
    data.put("added_at", row.createdAt());
    return data;
  }

  public record UpiCommand(String upiId, String nickname) {}

  public record CardCommand(
      String razorpayTokenId,
      String cardLast4,
      String cardNetwork,
      String cardType,
      String nickname) {}
}
