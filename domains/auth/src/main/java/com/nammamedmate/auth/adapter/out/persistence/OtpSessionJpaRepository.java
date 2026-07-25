package com.nammamedmate.auth.adapter.out.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OtpSessionJpaRepository extends JpaRepository<OtpSessionEntity, UUID> {}
