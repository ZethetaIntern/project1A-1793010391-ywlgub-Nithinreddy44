package com.payscale.httpe.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "phone_number", nullable = false, unique = true, length = 20)
    private String phoneNumber;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "kyc_status", nullable = false, length = 32)
    private String kycStatus = "PENDING";

    @Column(name = "kyc_tier", nullable = false, length = 32)
    private String kycTier = "MINIMUM";

    @Column(name = "risk_score", nullable = false)
    private Integer riskScore = 0;

    @Column(name = "pan_hash", length = 64)
    private String panHash;

    @Column(name = "aadhaar_vault_ref", length = 128)
    private String aadhaarVaultRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public User() {}

    public User(UUID userId, String phoneNumber, String email, String fullName, String kycStatus, String kycTier) {
        this.userId = userId != null ? userId : UUID.randomUUID();
        this.phoneNumber = phoneNumber;
        this.email = email;
        this.fullName = fullName;
        this.kycStatus = kycStatus != null ? kycStatus : "PENDING";
        this.kycTier = kycTier != null ? kycTier : "MINIMUM";
        this.riskScore = 0;
        this.createdAt = Instant.now();
    }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getKycStatus() { return kycStatus; }
    public void setKycStatus(String kycStatus) { this.kycStatus = kycStatus; }

    public String getKycTier() { return kycTier; }
    public void setKycTier(String kycTier) { this.kycTier = kycTier; }

    public Integer getRiskScore() { return riskScore; }
    public void setRiskScore(Integer riskScore) { this.riskScore = riskScore; }

    public String getPanHash() { return panHash; }
    public void setPanHash(String panHash) { this.panHash = panHash; }

    public String getAadhaarVaultRef() { return aadhaarVaultRef; }
    public void setAadhaarVaultRef(String aadhaarVaultRef) { this.aadhaarVaultRef = aadhaarVaultRef; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
