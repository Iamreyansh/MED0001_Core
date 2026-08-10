package com.nammamedmate.order.application.port.out;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Optional support-domain dispute banner for admin order detail (EPIC-015 STORY-002 AC-008).
 * Bridged from apps/api; no domain→domain compile dependency.
 */
public interface ExternalDisputeBannerPort {

  Optional<Map<String, Object>> findBanner(UUID orderId);
}
