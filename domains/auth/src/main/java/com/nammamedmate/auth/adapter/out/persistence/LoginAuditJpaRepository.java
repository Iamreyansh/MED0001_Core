package com.nammamedmate.auth.adapter.out.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface LoginAuditJpaRepository extends JpaRepository<LoginAuditEntity, UUID> {}
