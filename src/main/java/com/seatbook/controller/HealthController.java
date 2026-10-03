package com.seatbook.controller;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness: the process is up. Says nothing about dependencies.
 * Readiness: the DB actually answers. Fails closed (503) otherwise.
 */
@RestController
public class HealthController {

    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/healthz")
    public Map<String, String> healthz() {
        return Map.of("status", "UP");
    }

    @GetMapping("/readyz")
    public ResponseEntity<Map<String, String>> readyz() {
        try {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            if (one != null && one == 1) {
                return ResponseEntity.ok(Map.of("status", "READY"));
            }
        } catch (Exception e) {
            // fall through: any failure means not ready
        }
        return ResponseEntity.status(503).body(Map.of("status", "NOT_READY", "reason", "database unreachable"));
    }
}
