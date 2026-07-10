package com.instalego.repository;

import com.instalego.model.VerificationJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VerificationJobRepository extends JpaRepository<VerificationJob, Long> {
    List<VerificationJob> findByBankIdOrderByCreatedAtDesc(Long bankId);
}
