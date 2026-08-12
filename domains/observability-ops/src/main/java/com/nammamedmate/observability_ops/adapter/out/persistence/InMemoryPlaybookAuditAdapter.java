package com.nammamedmate.observability_ops.adapter.out.persistence;

import com.nammamedmate.observability_ops.application.port.out.PlaybookAuditPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** ponytail: in-memory audit + structured log; upgrade → durable audit table. */
@Component
public class InMemoryPlaybookAuditAdapter implements PlaybookAuditPort {

  private static final Logger log = LoggerFactory.getLogger(InMemoryPlaybookAuditAdapter.class);

  private final List<AuditEntry> entries = new ArrayList<>();

  @Override
  public void record(
      UUID playbookId, UUID updatedBy, Map<String, Object> before, Map<String, Object> after) {
    AuditEntry entry = new AuditEntry(playbookId, updatedBy, before, after);
    synchronized (entries) {
      entries.add(entry);
    }
    log.info(
        "observability.playbook.audit playbook_id={} updated_by={} before={} after={}",
        playbookId,
        updatedBy,
        before,
        after);
  }

  public List<AuditEntry> entries() {
    synchronized (entries) {
      return List.copyOf(entries);
    }
  }

  public void clear() {
    synchronized (entries) {
      entries.clear();
    }
  }

  public record AuditEntry(
      UUID playbookId, UUID updatedBy, Map<String, Object> before, Map<String, Object> after) {
    public AuditEntry {
      before = before == null ? Map.of() : Map.copyOf(before);
      after = after == null ? Map.of() : Map.copyOf(after);
    }
  }
}
