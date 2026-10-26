package com.payscale.httpe.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    @PostMapping("/events")
    public ResponseEntity<Map<String, String>> receiveWebhook(
        @RequestHeader(value = "X-PayScale-Signature", required = false) String signature,
        @RequestBody Map<String, Object> payload
    ) {
        log.info("Received external webhook event: type={}, signature={}", payload.get("event_type"), signature);

        // Security check: validate signature presence
        if (signature == null || signature.isBlank()) {
            log.warn("Rejected webhook: missing X-PayScale-Signature header");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", "INVALID_SIGNATURE"));
        }

        return ResponseEntity.ok(Map.of("status", "RECEIVED"));
    }
}
