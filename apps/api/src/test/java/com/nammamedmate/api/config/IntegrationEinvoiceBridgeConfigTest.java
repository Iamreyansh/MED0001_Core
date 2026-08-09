package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.integration.application.EinvoiceService;
import com.nammamedmate.integration.application.port.in.EinvoicePort;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IntegrationEinvoiceBridgeConfigTest {

  @Test
  void bridgesGenerateIrn() {
    EinvoiceService service = mock(EinvoiceService.class);
    UUID pharmacyId = UUID.randomUUID();
    UUID invoiceId = UUID.randomUUID();
    when(service.generateIrn(eq(pharmacyId), eq(invoiceId), any()))
        .thenReturn(Map.of("irn", "abc", "already_existed", false));
    EinvoicePort port = new IntegrationEinvoiceBridgeConfig().integrationEinvoicePort(service);
    assertThat(port.generateIrn(pharmacyId, invoiceId, Map.of()).get("irn")).isEqualTo("abc");
  }
}
