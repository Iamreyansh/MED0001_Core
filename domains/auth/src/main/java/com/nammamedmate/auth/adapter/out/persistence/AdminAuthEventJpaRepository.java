package com.nammamedmate.auth.adapter.out.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AdminAuthEventJpaRepository extends JpaRepository<AdminAuthEventEntity, UUID> {}
