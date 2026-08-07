package com.nammamedmate.pharmacy.application.port.out;

import java.util.UUID;

/** Hides/restores catalogue item visibility (EPIC-005 stub until catalogue mapping is wired). */
public interface CatalogueVisibilityPort {

  /** Sets all catalogue mappings for the pharmacy to not visible; returns count hidden. */
  int hideAll(UUID pharmacyId);

  /** Restores catalogue items to their previous visibility state. */
  void restoreAll(UUID pharmacyId);
}
