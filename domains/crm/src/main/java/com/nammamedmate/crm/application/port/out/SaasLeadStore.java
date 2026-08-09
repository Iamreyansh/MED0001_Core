package com.nammamedmate.crm.application.port.out;

import com.nammamedmate.crm.domain.CrmLead;
import com.nammamedmate.crm.domain.CrmLeadActivity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface SaasLeadStore {

  void insert(CrmLead lead);

  void update(CrmLead lead);

  Optional<CrmLead> findById(UUID id);

  boolean existsOpenByPhone(String phone, UUID excludeLeadId);

  boolean existsOpenByPharmacyId(UUID pharmacyId, UUID excludeLeadId);

  List<CrmLead> list(String stage, UUID repId, String source, String q, int offset, int limit);

  long count(String stage, UUID repId, String source, String q);

  PipelineChips chips(Instant periodFrom, Instant periodTo);

  Map<String, Long> openStageFunnel();

  void insertActivity(CrmLeadActivity activity);

  List<CrmLeadActivity> listActivities(UUID leadId);

  Optional<RepRef> findActiveRep(UUID repId);

  Optional<String> findRepName(UUID repId);

  List<UUID> listActiveRepIds();

  Optional<UUID> nextRoundRobinRepId();

  record RepRef(UUID id, String name) {}

  record PipelineChips(
      long openLeads,
      long pipelineMrrPaise,
      long weightedForecastMrrPaise,
      long avgDealMrrPaise,
      double winRatePct,
      double avgSalesCycleDays) {}
}
