package com.nammamedmate.pharmacy.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AdminNoteStore {

  record NoteRow(
      UUID id, UUID pharmacyId, String note, boolean flagged, UUID addedBy, Instant createdAt) {}

  void insert(NoteRow row);

  List<NoteRow> list(UUID pharmacyId, Boolean flaggedOnly, int limit, int offset);

  long count(UUID pharmacyId, Boolean flaggedOnly);
}
