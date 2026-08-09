package com.nammamedmate.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PurchaseOrderTest {

  @Test
  void editableOnlyWhenDraft() {
    Instant now = Instant.parse("2026-08-09T00:00:00Z");
    PurchaseOrder draft =
        new PurchaseOrder(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "PO-2026-08-000001",
            PurchaseOrderStatus.DRAFT,
            UUID.randomUUID(),
            null,
            null,
            null,
            now,
            now,
            null);
    assertThat(draft.editable()).isTrue();

    PurchaseOrder deletedDraft =
        new PurchaseOrder(
            draft.id(),
            draft.pharmacyId(),
            draft.distributorId(),
            draft.poNumber(),
            PurchaseOrderStatus.DRAFT,
            draft.createdBy(),
            null,
            null,
            null,
            now,
            now,
            now);
    assertThat(deletedDraft.editable()).isFalse();

    PurchaseOrder sent =
        new PurchaseOrder(
            draft.id(),
            draft.pharmacyId(),
            draft.distributorId(),
            draft.poNumber(),
            PurchaseOrderStatus.SENT,
            draft.createdBy(),
            now,
            PoSentChannel.EMAIL,
            null,
            now,
            now,
            null);
    assertThat(sent.editable()).isFalse();
  }
}
