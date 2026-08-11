package com.nammamedmate.analytics.adapter.out.client;

import com.nammamedmate.analytics.application.port.out.AcquisitionSourcePort;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Firebase UTM stub — every customer is ORGANIC until install events are wired. */
@Component
public class StubAcquisitionSourcePort implements AcquisitionSourcePort {

  @Override
  public Source sourceForCustomer(UUID customerId) {
    return Source.ORGANIC;
  }
}
