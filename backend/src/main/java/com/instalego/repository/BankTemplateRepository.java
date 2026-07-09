package com.instalego.repository;

import com.instalego.model.BankTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BankTemplateRepository extends JpaRepository<BankTemplate, Long> {
    List<BankTemplate> findByBankIdOrderByVersionDesc(Long bankId);
    Optional<BankTemplate> findTopByBankIdOrderByVersionDesc(Long bankId);
    boolean existsByBankId(Long bankId);
}
