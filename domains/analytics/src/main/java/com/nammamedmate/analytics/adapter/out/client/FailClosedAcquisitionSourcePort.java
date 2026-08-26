package com.nammamedmate.analytics.adapter.out.client;

import com.nammamedmate.analytics.application.port.out.AcquisitionSourcePort;
import com.nammamedmate.kernel.error.AppException;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Staging/prod: do not invent ORGANIC acquisition mix. */
@Component
@Profile({"prod", "staging"})
public class FailClosedAcquisitionSourcePort implements AcquisitionSourcePort {

  @Override
  public Source sourceForCustomer(UUID customerId) {
    throw new AppException(
        "ACQUISITION_SOURCE_UNAVAILABLE", "Acquisition attribution is not configured", 503);
  }
}
