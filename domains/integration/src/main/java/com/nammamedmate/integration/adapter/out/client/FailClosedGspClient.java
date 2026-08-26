package com.nammamedmate.integration.adapter.out.client;

import com.nammamedmate.integration.application.port.out.GspClientPort;
import com.nammamedmate.kernel.error.AppException;
import java.util.Map;
import java.util.Optional;

/** Staging/prod: never invent IRNs when GSP credentials are absent. */
public final class FailClosedGspClient implements GspClientPort {

  @Override
  public IrnResult generateIrn(Map<String, Object> invoiceData) {
    throw unavailable();
  }

  @Override
  public void cancelIrn(String irn, String cancelReasonCode, String cancelRemark) {
    throw unavailable();
  }

  @Override
  public IrnStatusResult getStatus(String irn) {
    throw unavailable();
  }

  @Override
  public TokenState refreshToken() {
    throw unavailable();
  }

  @Override
  public Optional<TokenState> currentToken() {
    return Optional.empty();
  }

  private static AppException unavailable() {
    return new AppException("GSP_UNAVAILABLE", "GSP provider is not configured", 503);
  }
}
