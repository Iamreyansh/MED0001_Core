package com.nammamedmate.rider.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.rider.application.port.out.RiderKycDocumentStore;
import com.nammamedmate.rider.application.port.out.RiderKycDocumentStore.DocumentRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class RiderKycExpirySchedulerTest {

  @Test
  void ac010_dispatchesAndMarksSent() {
    FakeDocs docs = new FakeDocs();
    InMemoryOutboxStore outbox = new InMemoryOutboxStore();
    Clock clock = Clock.fixed(Instant.parse("2026-07-24T01:00:00Z"), ZoneOffset.UTC);
    UUID riderId = Ids.newId();
    UUID docId = Ids.newId();
    docs.due.add(
        new DocumentRecord(
            docId,
            riderId,
            "VEHICLE_INSURANCE",
            null,
            "k",
            "u",
            1,
            "application/pdf",
            LocalDate.parse("2026-08-23"),
            false,
            "PENDING",
            null,
            clock.instant(),
            null,
            null));
    RiderKycExpiryScheduler scheduler =
        new RiderKycExpiryScheduler(docs, new OutboxPublisher(outbox, new ObjectMapper()), clock);
    scheduler.dispatchExpiryAlerts();
    assertThat(docs.marked).contains(docId);
    assertThat(outbox.all()).hasSize(1);
    assertThat(outbox.all().get(0).type()).contains("document_expiry");
  }

  static final class FakeDocs implements RiderKycDocumentStore {
    final List<DocumentRecord> due = new ArrayList<>();
    final Set<UUID> marked = ConcurrentHashMap.newKeySet();

    @Override
    public void insert(DocumentRecord doc) {}

    @Override
    public void softDelete(UUID id, Instant deletedAt) {}

    @Override
    public Optional<DocumentRecord> findActiveByRiderAndType(UUID riderId, String documentType) {
      return Optional.empty();
    }

    @Override
    public List<DocumentRecord> findActiveByRider(UUID riderId) {
      return List.of();
    }

    @Override
    public int countUploadsByRiderAndType(UUID riderId, String documentType) {
      return 0;
    }

    @Override
    public List<DocumentRecord> findDueForExpiryAlert(LocalDate onOrBefore, LocalDate after) {
      return due;
    }

    @Override
    public void markExpiryAlertSent(UUID documentId) {
      marked.add(documentId);
    }
  }
}
