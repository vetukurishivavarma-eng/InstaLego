package com.instalego.repository;

import com.instalego.model.ConversionJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ConversionJobRepository extends JpaRepository<ConversionJob, Long> {
}
