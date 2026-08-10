package com.nammamedmate.teleconsult.application.port.out;

import com.nammamedmate.teleconsult.domain.TeleconsultDoctor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TeleconsultDoctorStore {

  void insert(TeleconsultDoctor doctor);

  void update(TeleconsultDoctor doctor);

  Optional<TeleconsultDoctor> findById(UUID id);

  Optional<TeleconsultDoctor> findByRegistrationNo(String registrationNo);

  Page list(ListFilter filter);

  int resetConsultsToday();

  /** Available, non-deleted doctors (for NOW-slot assignment). */
  List<TeleconsultDoctor> listAvailable();

  record ListFilter(Boolean available, String specialty, int page, int limit) {}

  record Page(List<TeleconsultDoctor> items, long total) {
    public Page {
      items = items == null ? List.of() : List.copyOf(items);
    }
  }
}
