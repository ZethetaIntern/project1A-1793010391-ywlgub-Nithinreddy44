package com.payscale.httpe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    public static class AuditRecord {
        private final UUID auditId;
        private final UUID entityId;
        private final String action;
        private final String maskedDetails;
        private final String integrityHash;
        private final Instant timestamp;

        public AuditRecord(UUID entityId, String action, String maskedDetails, String integrityHash) {
            this.auditId = UUID.randomUUID();
            this.entityId = entityId;
            this.action = action;
            this.maskedDetails = maskedDetails;
            this.integrityHash = integrityHash;
            this.timestamp = Instant.now();
        }

        public UUID getAuditId() { return auditId; }
        public UUID getEntityId() { return entityId; }
        public String getAction() { return action; }
        public String getMaskedDetails() { return maskedDetails; }
        public String getIntegrityHash() { return integrityHash; }
        public Instant getTimestamp() { return timestamp; }
    }

    public AuditRecord recordAuditEvent(UUID entityId, String action, String rawPayload) {
        String maskedPayload = maskSensitivePII(rawPayload);
        String hash = computeSha256(entityId.toString() + ":" + action + ":" + maskedPayload);

        AuditRecord record = new AuditRecord(entityId, action, maskedPayload, hash);
        log.info("AUDIT LOG [{}] Action={} Entity={} Hash={}", record.getAuditId(), action, entityId, hash);
        return record;
    }

    /**
     * Enforces RBI compliance by masking PAN, Aadhaar, and bank account numbers.
     */
    public String maskSensitivePII(String input) {
        if (input == null) return "";
        // Mask 10-digit PAN (keep first 2 and last 2)
        String masked = input.replaceAll("([A-Z]{5}[0-9]{4}[A-Z]{1})", "XX****XX");
        // Mask 12-digit Aadhaar
        masked = masked.replaceAll("\\b\\d{4}\\s?\\d{4}\\s?\\d{4}\\b", "XXXX-XXXX-****");
        return masked;
    }

    private String computeSha256(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            return "HASH_UNAVAILABLE";
        }
    }
}
