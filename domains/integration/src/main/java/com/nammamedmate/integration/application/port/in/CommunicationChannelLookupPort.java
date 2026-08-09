package com.nammamedmate.integration.application.port.in;

import java.util.Optional;

/**
 * Inbound lookup for EPIC-017 delivery consumers. Control-plane status + active provider (with
 * automatic fallback when primary is DOWN).
 */
public interface CommunicationChannelLookupPort {

  Optional<ChannelSnapshot> find(String channel);

  /**
   * Provider to use for sends: primary when HEALTHY/DEGRADED and enabled; fallback when primary is
   * DOWN (or channel disabled with fallback); empty when dropped.
   */
  Optional<String> resolveActiveProvider(String channel);

  record ChannelSnapshot(
      String channel,
      boolean enabled,
      String provider,
      String fallbackProvider,
      String currentStatus,
      int dailySendLimit,
      int dailySentCount) {}
}
