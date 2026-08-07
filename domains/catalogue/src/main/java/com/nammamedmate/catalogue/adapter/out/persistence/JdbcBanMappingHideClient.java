package com.nammamedmate.catalogue.adapter.out.persistence;

import com.nammamedmate.catalogue.application.port.out.BanMappingHidePort;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class JdbcBanMappingHideClient implements BanMappingHidePort {

  private final MedicineMappingStore store;

  public JdbcBanMappingHideClient(MedicineMappingStore store) {
    this.store = store;
  }

  @Override
  public int hideAllForMedicine(UUID medicineId) {
    return store.hideAllForMedicine(medicineId);
  }
}
