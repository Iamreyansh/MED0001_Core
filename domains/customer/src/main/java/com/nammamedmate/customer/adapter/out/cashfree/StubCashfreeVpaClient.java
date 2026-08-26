package com.nammamedmate.customer.adapter.out.cashfree;

import com.nammamedmate.customer.application.port.out.CashfreeVpaPort;
import com.nammamedmate.kernel.error.AppException;

/**
 * Offline VPA validation for local/CI.
 *
 * <p>ponytail: real Cashfree when {@code medmate.cashfree.app-id} + {@code key-secret} are set (see
 * {@link CashfreeVpaClient}); upgrade path is EPIC-022 Cashfree integration.
 *
 * <ul>
 *   <li>{@code *timeout*} in the local part → {@code VPA_VALIDATION_TIMEOUT}
 *   <li>{@code *@invalid} handle → invalid
 *   <li>everything else → valid
 * </ul>
 */
public final class StubCashfreeVpaClient implements CashfreeVpaPort {

  @Override
  public boolean validateVpa(String vpa) {
    int at = vpa.indexOf('@');
    String local = at < 0 ? vpa : vpa.substring(0, at);
    String handle = at < 0 ? "" : vpa.substring(at + 1);
    if (local.contains("timeout")) {
      throw new AppException("VPA_VALIDATION_TIMEOUT", "Cashfree VPA validation timed out", 503);
    }
    return !"invalid".equalsIgnoreCase(handle);
  }
}
