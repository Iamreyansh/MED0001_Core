package com.nammamedmate.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditedEntityTest {

  @Test
  void softDeleteAndAuditHooks() {
    TestEntity entity = new TestEntity();
    assertThat(entity.isDeleted()).isFalse();
    entity.softDelete(Instant.parse("2026-01-01T00:00:00Z"));
    assertThat(entity.isDeleted()).isTrue();
    assertThat(entity.getDeletedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));

    entity.onCreate();
    assertThat(entity.getId()).isNotNull();
    assertThat(entity.getCreatedAt()).isNotNull();
    assertThat(entity.getUpdatedAt()).isNotNull();
    Instant created = entity.getCreatedAt();
    entity.onUpdate();
    assertThat(entity.getUpdatedAt()).isAfterOrEqualTo(created);

    TestEntity preset = new TestEntity();
    UUID id = UUID.randomUUID();
    preset.assignId(id);
    preset.onCreate();
    assertThat(preset.getId()).isEqualTo(id);
  }

  static final class TestEntity extends AuditedEntity {}
}
