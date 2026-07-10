package com.instalego.repository;

import com.instalego.model.LegalReference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LegalReferenceRepository extends JpaRepository<LegalReference, Long> {
    List<LegalReference> findByBankId(Long bankId);
    boolean existsByBankId(Long bankId);
}
