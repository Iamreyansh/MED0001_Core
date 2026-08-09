package com.nammamedmate.integration.application.port.in;

import java.util.Map;
import java.util.UUID;

/**
 * Thin inbound port for POS / finance bridges (apps/api composition). When {@code
 * e_invoicing_enabled} is false, returns {@code irn: null} without error (AC-005).
 */
public interface EinvoicePort {

  Map<String, Object> generateIrn(
      UUID pharmacyId, UUID platformInvoiceId, Map<String, Object> invoiceData);
}
