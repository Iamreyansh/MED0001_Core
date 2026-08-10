package com.nammamedmate.prescription.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.prescription.adapter.out.persistence.JdbcDoctorCardAdapter;
import com.nammamedmate.prescription.adapter.out.persistence.JdbcDoctorStore;
import com.nammamedmate.prescription.application.port.out.DoctorStore;
import com.nammamedmate.prescription.domain.DoctorRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcDoctorStoreCoverageTest {

  @Test
  void mutationsDelegateToJdbc() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcDoctorStore store = new JdbcDoctorStore(jdbc);
    Instant now = Instant.parse("2026-07-24T09:00:00Z");
    UUID id = Ids.newId();
    DoctorRecord d =
        new DoctorRecord(
            id,
            "MH1",
            "Dr X",
            "MBBS",
            "GP",
            "UNVERIFIED",
            "OCR",
            1,
            0,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            now,
            now,
            null);

    when(jdbc.update(anyString(), ArgumentMatchers.<Object>any())).thenReturn(1);
    store.insert(d);
    store.update(d);
    store.linkPrescription(Ids.newId(), id, true, now);
    store.markPendingBlacklist(id);
    store.incrementPrescriptionCount(id, now);
    store.incrementScheduledDrugCount(id, now);
    store.insertScheduleEvent(Ids.newId(), id, Ids.newId(), now);
    verify(jdbc, org.mockito.Mockito.atLeastOnce())
        .update(anyString(), ArgumentMatchers.<Object>any());

    assertThat(store.findByRegistrationNo(null)).isEmpty();
    assertThat(store.findByRegistrationNo("  ")).isEmpty();
  }

  @Test
  void doctorCardAdapterBranches() {
    Instant now = Instant.parse("2026-07-24T09:00:00Z");
    UUID id = Ids.newId();
    DoctorRecord d =
        new DoctorRecord(
            id,
            "MH1",
            "Dr Card",
            "MBBS",
            "GP",
            "VERIFIED",
            "OCR",
            1,
            0,
            "MANUAL",
            null,
            now,
            null,
            null,
            null,
            null,
            now,
            now,
            null);
    DoctorStoreFake fake = new DoctorStoreFake(d);
    JdbcDoctorCardAdapter card = new JdbcDoctorCardAdapter(fake);

    assertThat(card.findForPrescription(Ids.newId(), "UPLOADED", "Dr Loose", null))
        .isPresent()
        .get()
        .extracting(c -> c.name())
        .isEqualTo("Dr Loose");

    UUID rx = Ids.newId();
    fake.link = Optional.of(new DoctorStore.Link(rx, id, false, false));
    assertThat(card.findForPrescription(rx, "UPLOADED", "ignored", null))
        .isPresent()
        .get()
        .extracting(c -> c.registrationNo())
        .isEqualTo("MH1");
    assertThat(card.findRecord(rx)).contains(d);

    assertThat(card.findForPrescription(Ids.newId(), "E_PRESCRIPTION", null, Ids.newId()))
        .isPresent()
        .get()
        .extracting(c -> c.verified())
        .isEqualTo(true);

    assertThat(card.findForPrescription(Ids.newId(), "UPLOADED", null, null)).isEmpty();
  }

  static final class DoctorStoreFake implements DoctorStore {
    final DoctorRecord d;
    Optional<Link> link = Optional.empty();

    DoctorStoreFake(DoctorRecord d) {
      this.d = d;
    }

    @Override
    public void insert(DoctorRecord doctor) {}

    @Override
    public void update(DoctorRecord doctor) {}

    @Override
    public Optional<DoctorRecord> findById(UUID id) {
      return Optional.of(d);
    }

    @Override
    public Optional<DoctorRecord> findByRegistrationNo(String registrationNo) {
      return Optional.empty();
    }

    @Override
    public Page list(ListFilter filter) {
      return new Page(List.of(), 0);
    }

    @Override
    public Page listUnverified(int page, int limit) {
      return new Page(List.of(), 0);
    }

    @Override
    public void linkPrescription(
        UUID rxId, UUID doctorId, boolean unrecognizedQualification, Instant createdAt) {}

    @Override
    public Optional<Link> findLink(UUID rxId) {
      return link.filter(l -> l.rxId().equals(rxId));
    }

    @Override
    public void markPendingBlacklist(UUID doctorId) {}

    @Override
    public List<UUID> listRxIdsForDoctor(UUID doctorId) {
      return List.of();
    }

    @Override
    public int countRxForDoctor(UUID doctorId) {
      return 0;
    }

    @Override
    public void incrementPrescriptionCount(UUID doctorId, Instant updatedAt) {}

    @Override
    public void incrementScheduledDrugCount(UUID doctorId, Instant updatedAt) {}

    @Override
    public void insertScheduleEvent(UUID eventId, UUID doctorId, UUID rxId, Instant createdAt) {}

    @Override
    public long countScheduleEventsSince(UUID doctorId, Instant since) {
      return 0;
    }

    @Override
    public Map<String, Integer> prescriptionCategoryCounts(UUID doctorId) {
      return Map.of();
    }

    @Override
    public ScheduleCounts scheduleCounts(UUID doctorId) {
      return new ScheduleCounts(0, 0, 0);
    }

    @Override
    public long associatedOrdersCount(UUID doctorId) {
      return 0;
    }
  }
}
