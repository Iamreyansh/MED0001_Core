package com.nammamedmate.integration.application.port.out;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** GST Suvidha Provider → NIC e-invoice portal. */
public interface GspClientPort {

  record IrnResult(
      String irn, String ackNumber, Instant ackDate, String qrCodeUrl, String signedInvoiceJson) {}

  record IrnStatusResult(
      String irn, String status, String ackNumber, Instant ackDate, Instant cancelledAt) {}

  record TokenState(String accessToken, Instant expiresAt) {}

  IrnResult generateIrn(Map<String, Object> invoiceData);

  void cancelIrn(String irn, String cancelReasonCode, String cancelRemark);

  IrnStatusResult getStatus(String irn);

  /** Refresh GSP JWT; returns new expiry. Stub no-ops with far-future expiry. */
  TokenState refreshToken();

  Optional<TokenState> currentToken();
}
