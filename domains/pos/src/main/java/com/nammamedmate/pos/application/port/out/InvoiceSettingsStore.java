package com.nammamedmate.pos.application.port.out;

import com.nammamedmate.pos.domain.InvoiceSettings;
import java.util.UUID;

public interface InvoiceSettingsStore {

  InvoiceSettings getOrCreate(UUID pharmacyId);

  InvoiceSettings upsert(InvoiceSettings settings);
}
