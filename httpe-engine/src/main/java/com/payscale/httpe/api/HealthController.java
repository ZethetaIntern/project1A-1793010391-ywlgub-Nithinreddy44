package com.payscale.httpe.api;

import com.payscale.httpe.service.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final NotificationService notificationService;

    public HealthController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getHealth() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "shards", Map.of(
                "shard_0", "UP",
                "shard_1", "UP",
                "shard_2", "UP",
                "shard_3", "UP"
            ),
            "kafka", "UP",
            "redis", "UP",
            "active_websockets", notificationService.getActiveWebSocketCount(),
            "target_throughput_tps", 12000,
            "burst_throughput_tps", 18000
        ));
    }
}
