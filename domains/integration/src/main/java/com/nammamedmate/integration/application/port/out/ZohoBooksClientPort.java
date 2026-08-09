package com.nammamedmate.integration.application.port.out;

import com.nammamedmate.integration.domain.AccountingVoucher;
import java.time.Instant;

public interface ZohoBooksClientPort {

  record TokenPair(String accessToken, String refreshToken, Instant expiresAt) {}

  record SyncResult(boolean created, String voucherId, String errorCode, String errorMessage) {
    public static SyncResult ok(String voucherId, boolean created) {
      return new SyncResult(created, voucherId, null, null);
    }

    public static SyncResult fail(String errorCode, String errorMessage) {
      return new SyncResult(false, null, errorCode, errorMessage);
    }
  }

  TokenPair refreshAccessToken(String refreshToken);

  /** Upsert sales voucher by platform_id external reference (idempotent). */
  SyncResult upsertSalesVoucher(
      String accessToken, String organizationId, AccountingVoucher voucher);

  SyncResult upsertPurchaseVoucher(
      String accessToken, String organizationId, AccountingVoucher voucher);

  SyncResult upsertGstEntry(String accessToken, String organizationId, AccountingVoucher voucher);

  SyncResult upsertExpense(String accessToken, String organizationId, AccountingVoucher voucher);
}
