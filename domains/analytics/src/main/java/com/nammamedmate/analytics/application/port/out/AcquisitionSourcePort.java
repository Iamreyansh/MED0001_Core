package com.nammamedmate.analytics.application.port.out;

import java.util.UUID;

/**
 * Resolves install/acquisition source for a customer (Firebase UTM). Stub defaults to ORGANIC until
 * Firebase wiring lands.
 */
public interface AcquisitionSourcePort {

  enum Source {
    ORGANIC,
    REFERRAL,
    AD,
    PARTNER
  }

  Source sourceForCustomer(UUID customerId);
}
