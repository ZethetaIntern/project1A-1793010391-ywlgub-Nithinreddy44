package com.payscale.httpe.repository;

import com.payscale.httpe.domain.FraudRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FraudRuleRepository extends JpaRepository<FraudRule, String> {
    List<FraudRule> findByIsActiveTrue();
}
