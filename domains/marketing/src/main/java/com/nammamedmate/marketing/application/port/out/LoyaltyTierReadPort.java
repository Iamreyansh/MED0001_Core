package com.nammamedmate.marketing.application.port.out;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Loyalty tier read; defaults to NONE until STORY-006. */
public interface LoyaltyTierReadPort {

  Map<UUID, String> tiersFor(Collection<UUID> customerIds);
}
