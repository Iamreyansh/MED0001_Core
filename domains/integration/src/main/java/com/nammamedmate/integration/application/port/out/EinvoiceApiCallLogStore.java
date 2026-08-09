package com.nammamedmate.integration.application.port.out;

import com.nammamedmate.integration.domain.EinvoiceApiCallLog;

public interface EinvoiceApiCallLogStore {

  void insert(EinvoiceApiCallLog log);
}
