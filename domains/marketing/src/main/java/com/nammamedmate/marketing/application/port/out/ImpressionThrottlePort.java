package com.nammamedmate.marketing.application.port.out;

import java.util.UUID;

/**
 * Throttles banner impressions to one per (banner, customer, session) / 30 minutes.
 *
 * @return true if this call should increment the impression counter
 */
public interface ImpressionThrottlePort {

  boolean tryAcquire(UUID bannerId, UUID customerId, String sessionId);
}
