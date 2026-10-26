package com.payscale.httpe.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "fraud_rules")
public class FraudRule {

    @Id
    @Column(name = "rule_id", nullable = false, length = 64)
    private String ruleId;

    @Column(name = "rule_name", nullable = false)
    private String ruleName;

    @Column(name = "rule_type", nullable = false, length = 32)
    private String ruleType;

    @Column(name = "parameters", nullable = false, columnDefinition = "TEXT")
    private String parameters;

    @Column(name = "action", nullable = false, length = 32)
    private String action;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public FraudRule() {}

    public FraudRule(String ruleId, String ruleName, String ruleType, String parameters, String action, Boolean isActive) {
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.ruleType = ruleType;
        this.parameters = parameters;
        this.action = action;
        this.isActive = isActive != null ? isActive : true;
        this.createdAt = Instant.now();
    }

    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }

    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }

    public String getRuleType() { return ruleType; }
    public void setRuleType(String ruleType) { this.ruleType = ruleType; }

    public String getParameters() { return parameters; }
    public void setParameters(String parameters) { this.parameters = parameters; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
