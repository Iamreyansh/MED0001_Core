package com.nammamedmate.catalogue.application.port.out;

import java.util.UUID;

/**
 * Counts active (non-banned) medicines mapped to a category.
 *
 * <p>STORY-002 ships a stub returning 0; STORY-001 replaces with a JDBC impl against {@code
 * medicine_master}.
 */
public interface ActiveMedicineCountPort {

  int countActiveByCategoryId(UUID categoryId);
}
