package com.nammamedmate.pos.application;

import com.nammamedmate.pos.application.port.out.PosCartStore;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "medmate.pos.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PosCartExpiryScheduler {

  private final PosCartStore cartStore;
  private final Clock clock;

  public PosCartExpiryScheduler(PosCartStore cartStore, Clock clock) {
    this.cartStore = cartStore;
    this.clock = clock;
  }

  /** Every 15 minutes Asia/Kolkata — mark expired ACTIVE carts as ABANDONED. */
  @Scheduled(cron = "0 */15 * * * *", zone = "Asia/Kolkata")
  public void abandonExpiredCarts() {
    cartStore.abandonExpired(clock.instant());
  }
}
