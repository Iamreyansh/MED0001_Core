package com.nammamedmate.customer.adapter.out.razorpay;

import com.nammamedmate.customer.application.port.out.RazorpayVpaPort;
import com.nammamedmate.kernel.error.AppException;

/**
 * Offline VPA validation for local/CI.
 *
 * <p>ponytail: real Razorpay when {@code medmate.razorpay.key-id} + {@code key-secret} are set (see
 * {@link RazorpayVpaClient}); upgrade path is EPIC-022 Razorpay integration.
 *
 * <ul>
 *   <li>{@code *timeout*} in the local part → {@code VPA_VALIDATION_TIMEOUT}
 *   <li>{@code *@invalid} handle → invalid
 *   <li>everything else → valid
 * </ul>
 */
public final class StubRazorpayVpaClient implements RazorpayVpaPort {

  @Override
  public boolean validateVpa(String vpa) {
    int at = vpa.indexOf('@');
    String local = at < 0 ? vpa : vpa.substring(0, at);
    String handle = at < 0 ? "" : vpa.substring(at + 1);
    if (local.contains("timeout")) {
      throw new AppException("VPA_VALIDATION_TIMEOUT", "Razorpay VPA validation timed out", 503);
    }
    return !"invalid".equalsIgnoreCase(handle);
  }
}
