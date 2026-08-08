package com.nammamedmate.rider.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CodDepositStore {

  record DepositRecord(
      UUID id,
      UUID riderId,
      long amountPaise,
      String depositMode,
      String referenceNumber,
      String status,
      Instant submittedAt,
      Instant confirmedAt,
      UUID confirmedBy,
      Instant depositedAt,
      String notes,
      Instant createdAt,
      Instant updatedAt) {}

  record CodBoardRow(
      UUID riderId,
      String riderName,
      UUID zoneId,
      String zoneName,
      long codInHandPaise,
      long collectedTodayPaise,
      long depositedTodayPaise,
      int tripsToday,
      Instant lastDepositAt) {}

  record BoardPage(List<CodBoardRow> rows, long total) {
    public BoardPage {
      rows = List.copyOf(rows);
    }
  }

  void insert(DepositRecord row);

  void update(DepositRecord row);

  Optional<DepositRecord> findById(UUID id);

  Optional<DepositRecord> findByReference(String referenceNumber);

  Optional<DepositRecord> findPendingByReference(UUID riderId, String referenceNumber);

  boolean referenceExists(String referenceNumber);

  long sumDepositedToday(UUID riderId, Instant dayStart, Instant dayEnd);

  long sumDepositedTodayAll(Instant dayStart, Instant dayEnd);

  long sumPendingDepositRequests(Instant dayStart, Instant dayEnd);

  int countFloatRiskRiders(long limitPaise);

  long sumCodInHandAll();

  Instant lastConfirmedDepositAt(UUID riderId);

  BoardPage listBoard(UUID zoneId, boolean riskOnly, long limitPaise, int page, int limit);

  List<CodBoardRow> allForReport(long limitPaise);
}
