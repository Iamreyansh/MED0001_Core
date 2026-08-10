package com.nammamedmate.support.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Banner for admin order detail when a support dispute exists (AC-008). */
public interface SupportDisputeBannerPort {

  record Banner(
      String disputeId,
      String status,
      String disputeType,
      String liableParty,
      String description,
      Instant createdAt) {}

  Optional<Banner> findForOrder(UUID orderId);
}
