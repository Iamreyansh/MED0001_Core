package com.nammamedmate.catalogue.adapter.out.persistence;

import com.nammamedmate.catalogue.application.port.out.ActiveMedicineCountPort;
import com.nammamedmate.catalogue.application.port.out.MedicineStore;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class JdbcActiveMedicineCountClient implements ActiveMedicineCountPort {

  private final MedicineStore store;

  public JdbcActiveMedicineCountClient(MedicineStore store) {
    this.store = store;
  }

  @Override
  public int countActiveByCategoryId(UUID categoryId) {
    return store.countActiveByCategoryId(categoryId);
  }
}
