package com.nammamedmate.customer.application.port.out;

/** Razorpay VPA validation ({@code GET /v1/payments/validate/vpa}). */
@FunctionalInterface
public interface RazorpayVpaPort {

  /**
   * @return {@code true} when Razorpay reports a valid VPA
   * @throws com.nammamedmate.kernel.error.AppException with {@code VPA_VALIDATION_TIMEOUT} on
   *     timeout
   */
  boolean validateVpa(String vpa);
}
