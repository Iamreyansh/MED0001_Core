package com.nammamedmate.catalogue.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JdbcBanMappingHideClientTest {

  @Mock private MedicineMappingStore store;

  @Test
  void delegatesHide() {
    UUID id = UUID.randomUUID();
    when(store.hideAllForMedicine(id)).thenReturn(7);
    assertThat(new JdbcBanMappingHideClient(store).hideAllForMedicine(id)).isEqualTo(7);
    verify(store).hideAllForMedicine(id);
  }
}
