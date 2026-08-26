package com.nammamedmate.customer.application.port.out;

/** Cashfree VPA validation ({@code GET /v1/payments/validate/vpa}). */
@FunctionalInterface
public interface CashfreeVpaPort {

  /**
   * @return {@code true} when Cashfree reports a valid VPA
   * @throws com.nammamedmate.kernel.error.AppException with {@code VPA_VALIDATION_TIMEOUT} on
   *     timeout
   */
  boolean validateVpa(String vpa);
}
