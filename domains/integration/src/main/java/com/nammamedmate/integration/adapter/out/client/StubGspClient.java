package com.nammamedmate.integration.adapter.out.client;

import com.nammamedmate.integration.application.port.out.GspClientPort;
import com.nammamedmate.kernel.error.AppException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic GSP stub.
 *
 * <ul>
 *   <li>seller GSTIN containing {@code 0000} → {@code SELLER_GSTIN_NOT_REGISTERED}
 *   <li>seller GSTIN containing {@code 9999} → {@code NIC_PORTAL_UNAVAILABLE}
 *   <li>IRN starting with {@code dead} → not found on cancel/status
 * </ul>
 */
public final class StubGspClient implements GspClientPort {

  private final Clock clock;
  private final boolean forceUnavailable;
  private final AtomicLong ackSeq = new AtomicLong(232410141234567L);
  private final ConcurrentHashMap<String, IrnStatusResult> portal = new ConcurrentHashMap<>();
  private volatile TokenState token;

  public StubGspClient(Clock clock) {
    this(clock, false);
  }

  public StubGspClient(Clock clock, boolean forceUnavailable) {
    this.clock = clock;
    this.forceUnavailable = forceUnavailable;
    this.token = new TokenState("stub-gsp-token", clock.instant().plus(Duration.ofHours(24)));
  }

  @Override
  public IrnResult generateIrn(Map<String, Object> invoiceData) {
    if (forceUnavailable) {
      throw unavailable();
    }
    String seller = str(invoiceData.get("seller_gstin")).toUpperCase(Locale.ROOT);
    if (seller.contains("9999")) {
      throw unavailable();
    }
    if (seller.contains("0000")) {
      throw new AppException(
          "SELLER_GSTIN_NOT_REGISTERED", "Seller GSTIN not found in e-invoice portal", 422);
    }
    String buyer = str(invoiceData.get("buyer_gstin")).toUpperCase(Locale.ROOT);
    String invoiceNumber = str(invoiceData.get("invoice_number"));
    String invoiceDate = str(invoiceData.get("invoice_date"));
    Instant now = clock.instant();
    String irn = sha256Hex(seller + "|" + buyer + "|" + invoiceNumber + "|" + invoiceDate);
    String ack = Long.toString(ackSeq.getAndIncrement());
    String signed =
        "{\"Version\":\"1.1\",\"Irn\":\""
            + irn
            + "\",\"AckNo\":\""
            + ack
            + "\",\"SellerGstin\":\""
            + seller
            + "\",\"BuyerGstin\":\""
            + buyer
            + "\",\"DocNo\":\""
            + invoiceNumber
            + "\",\"Signature\":\"MEUCIQStubNicSig"
            + irn.substring(0, 16)
            + "\"}";
    String qr =
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
    portal.put(irn, new IrnStatusResult(irn, "ACTIVE", ack, now, null));
    return new IrnResult(irn, ack, now, qr, signed);
  }

  @Override
  public void cancelIrn(String irn, String cancelReasonCode, String cancelRemark) {
    if (forceUnavailable) {
      throw unavailable();
    }
    String key = irn == null ? "" : irn;
    if (key.toLowerCase(Locale.ROOT).startsWith("dead")) {
      throw new AppException("IRN_NOT_FOUND", "IRN not found in NIC portal", 404);
    }
    Instant now = clock.instant();
    try {
      portal.compute(
          key,
          (k, current) -> {
            IrnStatusResult base =
                current == null
                    ? new IrnStatusResult(k, "ACTIVE", "0", clock.instant(), null)
                    : current;
            if ("CANCELLED".equals(base.status())) {
              throw new CancelAlreadyException();
            }
            return new IrnStatusResult(k, "CANCELLED", base.ackNumber(), base.ackDate(), now);
          });
    } catch (CancelAlreadyException e) {
      throw new AppException("IRN_ALREADY_CANCELLED", "IRN already cancelled", 422);
    }
  }

  private static final class CancelAlreadyException extends RuntimeException {}

  @Override
  public IrnStatusResult getStatus(String irn) {
    if (forceUnavailable) {
      throw unavailable();
    }
    String key = irn == null ? "" : irn;
    if (key.toLowerCase(Locale.ROOT).startsWith("dead")) {
      throw new AppException("IRN_NOT_FOUND", "IRN not found in NIC portal", 404);
    }
    IrnStatusResult local = portal.get(key);
    if (local != null) {
      return local;
    }
    throw new AppException("IRN_NOT_FOUND", "IRN not found in NIC portal", 404);
  }

  @Override
  public TokenState refreshToken() {
    if (forceUnavailable) {
      throw unavailable();
    }
    TokenState next =
        new TokenState(
            "stub-gsp-token-" + clock.millis(), clock.instant().plus(Duration.ofHours(24)));
    this.token = next;
    return next;
  }

  @Override
  public Optional<TokenState> currentToken() {
    return Optional.ofNullable(token);
  }

  private static AppException unavailable() {
    return new AppException("NIC_PORTAL_UNAVAILABLE", "NIC portal unreachable", 503);
  }

  private static String str(Object v) {
    return v == null ? "" : v.toString().trim();
  }

  private static String sha256Hex(String input) {
    return digestHex(input, "SHA-256");
  }

  /** Visible for tests — pass a bogus algorithm to hit the failure path. */
  static String digestHex(String input, String algorithm) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance(algorithm).digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
