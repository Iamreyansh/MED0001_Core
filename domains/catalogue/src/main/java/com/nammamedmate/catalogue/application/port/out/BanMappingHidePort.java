package com.nammamedmate.catalogue.application.port.out;

import java.util.UUID;

/**
 * Hides pharmacy catalogue mappings when a medicine is banned.
 *
 * <p>STORY-001 ships a no-op stub; STORY-005 replaces with real {@code pharmacy_catalogue_mapping}
 * updates.
 */
public interface BanMappingHidePort {

  /**
   * @return number of mappings hidden
   */
  int hideAllForMedicine(UUID medicineId);
}
