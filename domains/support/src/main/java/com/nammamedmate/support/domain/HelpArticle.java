package com.nammamedmate.support.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record HelpArticle(
    UUID id,
    String title,
    TicketCategory category,
    String contentMarkdown,
    List<String> tags,
    boolean published,
    int viewCount,
    int deflectionCount,
    UUID createdBy,
    Instant deletedAt,
    Instant createdAt,
    Instant updatedAt) {

  public HelpArticle {
    tags = tags == null ? List.of() : List.copyOf(tags);
  }

  public HelpArticle withContent(
      String title,
      TicketCategory category,
      String contentMarkdown,
      List<String> tags,
      Boolean published,
      Instant updatedAt) {
    return new HelpArticle(
        id,
        title == null ? this.title : title,
        category == null ? this.category : category,
        contentMarkdown == null ? this.contentMarkdown : contentMarkdown,
        tags == null ? this.tags : tags,
        published == null ? this.published : published,
        viewCount,
        deflectionCount,
        createdBy,
        deletedAt,
        createdAt,
        updatedAt);
  }

  public HelpArticle withViewCount(int viewCount) {
    return new HelpArticle(
        id,
        title,
        category,
        contentMarkdown,
        tags,
        published,
        viewCount,
        deflectionCount,
        createdBy,
        deletedAt,
        createdAt,
        updatedAt);
  }

  public HelpArticle withDeflectionCount(int deflectionCount) {
    return new HelpArticle(
        id,
        title,
        category,
        contentMarkdown,
        tags,
        published,
        viewCount,
        deflectionCount,
        createdBy,
        deletedAt,
        createdAt,
        updatedAt);
  }
}
