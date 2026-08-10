package com.nammamedmate.prescription.application.port.out;

import com.nammamedmate.prescription.domain.PrescriptionRecord;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PrescriptionStore {

  void insert(PrescriptionRecord record);

  Optional<PrescriptionRecord> findByIdForCustomer(UUID id, UUID customerId);

  Optional<PrescriptionRecord> findById(UUID id);

  record Page(List<PrescriptionRecord> items, long total) {
    public Page {
      items = items == null ? List.of() : List.copyOf(items);
    }
  }

  Page listForCustomer(
      UUID customerId, String status, String type, int page, int limit, String sort, String order);

  void softDelete(UUID id, Instant deletedAt, Instant updatedAt);

  void updateOcr(
      UUID id,
      String doctorName,
      LocalDate prescriptionDate,
      List<PrescriptionRecord.MedicineExtracted> medicines,
      Instant updatedAt);

  int markExpiredDue(Instant now, Instant updatedAt);
}
