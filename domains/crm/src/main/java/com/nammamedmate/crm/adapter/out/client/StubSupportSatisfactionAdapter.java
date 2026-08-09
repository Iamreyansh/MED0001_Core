package com.nammamedmate.crm.adapter.out.client;

import com.nammamedmate.crm.application.port.out.SupportSatisfactionPort;
import com.nammamedmate.crm.domain.HealthMath;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** ponytail: default support=100 until EPIC-015 NPS/tickets. */
@Component
public class StubSupportSatisfactionAdapter implements SupportSatisfactionPort {

  @Override
  public double scoreForAccount(UUID accountId) {
    return HealthMath.DEFAULT_SUPPORT;
  }
}
