package com.nammamedmate.support.domain;

import java.time.Instant;
import java.util.UUID;

public record CannedResponse(
    UUID id,
    String title,
    TicketCategory category,
    String body,
    String shortcutKey,
    int copyCount,
    Instant lastUsedAt,
    UUID createdBy,
    Instant deletedAt,
    Instant createdAt,
    Instant updatedAt) {

  public CannedResponse withUsage(int copyCount, Instant lastUsedAt, Instant updatedAt) {
    return new CannedResponse(
        id,
        title,
        category,
        body,
        shortcutKey,
        copyCount,
        lastUsedAt,
        createdBy,
        deletedAt,
        createdAt,
        updatedAt);
  }

  public CannedResponse withContent(
      String title, TicketCategory category, String body, String shortcutKey, Instant updatedAt) {
    return new CannedResponse(
        id,
        title == null ? this.title : title,
        category == null ? this.category : category,
        body == null ? this.body : body,
        shortcutKey == null ? this.shortcutKey : shortcutKey,
        copyCount,
        lastUsedAt,
        createdBy,
        deletedAt,
        createdAt,
        updatedAt);
  }

  public CannedResponse softDeleted(Instant deletedAt, Instant updatedAt) {
    return new CannedResponse(
        id,
        title,
        category,
        body,
        shortcutKey,
        copyCount,
        lastUsedAt,
        createdBy,
        deletedAt,
        createdAt,
        updatedAt);
  }
}
