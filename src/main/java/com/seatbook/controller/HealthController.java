package com.seatbook.controller;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
    private static final ExecutorService PROBE = Executors.newVirtualThreadPerTaskExecutor();

    @GetMapping("/healthz")
    public Map<String, String> healthz() {
        return Map.of("status", "UP");
    }

    @GetMapping("/readyz")
    public ResponseEntity<Map<String, String>> readyz() {
        Future<Integer> check = PROBE.submit(() -> jdbc.queryForObject("SELECT 1", Integer.class));
        try {
            Integer one = check.get(2, TimeUnit.SECONDS);
            if (one != null && one == 1) return ResponseEntity.ok(Map.of("status", "READY"));
        } catch (Exception e) {
            check.cancel(true);
        }
        return ResponseEntity.status(503).body(Map.of("status", "NOT_READY", "reason", "database unreachable"));
    }
}
