package com.nammamedmate.messaging;

/** Deduplicates provider webhooks by provider + event id. */
public interface WebhookInbox {

  boolean alreadyReceived(String provider, String providerEventId);

  /** @return false when this provider event was already received. */
  boolean claim(String provider, String providerEventId, String payloadJson);
}
