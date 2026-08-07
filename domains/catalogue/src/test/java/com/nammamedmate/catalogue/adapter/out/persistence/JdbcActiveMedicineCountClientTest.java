package com.nammamedmate.catalogue.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.catalogue.application.port.out.MedicineStore;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JdbcActiveMedicineCountClientTest {

  @Test
  void delegatesToStore() {
    MedicineStore store = mock(MedicineStore.class);
    UUID id = UUID.randomUUID();
    when(store.countActiveByCategoryId(id)).thenReturn(7);
    assertThat(new JdbcActiveMedicineCountClient(store).countActiveByCategoryId(id)).isEqualTo(7);
  }
}
