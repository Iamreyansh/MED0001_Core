package com.nammamedmate.crm.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Cached HTTP responses for subscription charge mutators (subscribe / upgrade). */
public interface SaasSubscriptionIdempotencyStore {

  String OP_SUBSCRIBE = "SUBSCRIBE";
  String OP_UPGRADE = "UPGRADE";

  record CachedResponse(
      String idempotencyKey, UUID accountId, String operation, String responseJson) {}

  Optional<CachedResponse> findByKey(String idempotencyKey);

  void insert(
      String idempotencyKey,
      UUID accountId,
      String operation,
      String responseJson,
      Instant createdAt);
}
