package com.nammamedmate.prescription.application.port.out;

import com.nammamedmate.prescription.domain.DoctorRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface DoctorStore {

  void insert(DoctorRecord doctor);

  void update(DoctorRecord doctor);

  Optional<DoctorRecord> findById(UUID id);

  Optional<DoctorRecord> findByRegistrationNo(String registrationNo);

  record ListFilter(
      String search,
      String specialty,
      String status,
      int page,
      int limit,
      String sort,
      String order) {}

  record Page(List<DoctorRecord> items, long total) {
    public Page {
      items = items == null ? List.of() : List.copyOf(items);
    }
  }

  Page list(ListFilter filter);

  Page listUnverified(int page, int limit);

  void linkPrescription(
      UUID rxId, UUID doctorId, boolean unrecognizedQualification, Instant createdAt);

  Optional<Link> findLink(UUID rxId);

  record Link(
      UUID rxId, UUID doctorId, boolean unrecognizedQualification, boolean pendingBlacklistFlag) {}

  void markPendingBlacklist(UUID doctorId);

  List<UUID> listRxIdsForDoctor(UUID doctorId);

  int countRxForDoctor(UUID doctorId);

  void incrementPrescriptionCount(UUID doctorId, Instant updatedAt);

  void incrementScheduledDrugCount(UUID doctorId, Instant updatedAt);

  void insertScheduleEvent(UUID eventId, UUID doctorId, UUID rxId, Instant createdAt);

  long countScheduleEventsSince(UUID doctorId, Instant since);

  Map<String, Integer> prescriptionCategoryCounts(UUID doctorId);

  record ScheduleCounts(int scheduledH, int scheduledH1, int scheduledX) {}

  ScheduleCounts scheduleCounts(UUID doctorId);

  long associatedOrdersCount(UUID doctorId);
}
