package com.nammamedmate.catalogue.application.port.out;

import java.util.Optional;
import java.util.UUID;

public interface SearchCachePort {

  Optional<String> getAutocomplete(String normalizedQuery);

  void putAutocomplete(String normalizedQuery, String json);

  Optional<String> getMedicineDetail(UUID medicineId);

  void putMedicineDetail(UUID medicineId, String json);
}
