package com.nammamedmate.integration.adapter.out.client;

import com.nammamedmate.integration.application.port.out.ZohoBooksClientPort;
import com.nammamedmate.integration.domain.AccountingVoucher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic Zoho Books stub.
 *
 * <ul>
 *   <li>party GSTIN containing {@code INVALID} → {@code INVALID_CUSTOMER_GSTIN}
 *   <li>external {@code platform_id} dedupe — second upsert returns {@code created=false}
 * </ul>
 */
public final class StubZohoBooksClient implements ZohoBooksClientPort {

  private final Clock clock;
  private final Map<String, String> vouchersByPlatformId = new ConcurrentHashMap<>();
  private final AtomicInteger refreshCount = new AtomicInteger();

  public StubZohoBooksClient(Clock clock) {
    this.clock = clock;
  }

  public int refreshCount() {
    return refreshCount.get();
  }

  public int voucherCount() {
    return vouchersByPlatformId.size();
  }

  @Override
  public TokenPair refreshAccessToken(String refreshToken) {
    refreshCount.incrementAndGet();
    Instant expires = Instant.now(clock).plus(Duration.ofHours(1));
    return new TokenPair("stub-access-" + refreshCount.get(), refreshToken, expires);
  }

  @Override
  public SyncResult upsertSalesVoucher(
      String accessToken, String organizationId, AccountingVoucher voucher) {
    return upsert("SALES", voucher);
  }

  @Override
  public SyncResult upsertPurchaseVoucher(
      String accessToken, String organizationId, AccountingVoucher voucher) {
    return upsert("PURCHASE", voucher);
  }

  @Override
  public SyncResult upsertGstEntry(
      String accessToken, String organizationId, AccountingVoucher voucher) {
    return upsert("GST", voucher);
  }

  @Override
  public SyncResult upsertExpense(
      String accessToken, String organizationId, AccountingVoucher voucher) {
    return upsert("EXPENSE", voucher);
  }

  private SyncResult upsert(String kind, AccountingVoucher voucher) {
    String gstin =
        voucher.partyGstin() == null ? "" : voucher.partyGstin().toUpperCase(Locale.ROOT);
    if (gstin.contains("INVALID")) {
      return SyncResult.fail(
          "INVALID_CUSTOMER_GSTIN",
          "Customer GSTIN " + voucher.partyGstin() + " is not a valid GST number");
    }
    String key = kind + "|" + voucher.platformId();
    String existing = vouchersByPlatformId.get(key);
    if (existing != null) {
      return SyncResult.ok(existing, false);
    }
    String id = "zoho_" + kind.toLowerCase(Locale.ROOT) + "_" + voucher.platformId();
    vouchersByPlatformId.put(key, id);
    return SyncResult.ok(id, true);
  }
}
