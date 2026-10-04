package com.seatbook.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Thin HTTP client for the seatbook API. Token = user name, as the service accepts. */
public class TestClient {

    public static final String ADMIN = "admin-token";
    private static final Semaphore IN_FLIGHT = new Semaphore(200);
    public static final AtomicLong CONNECT_RETRIES = new AtomicLong();

    public record Counts(int total, int available, int held, int confirmed) {
        public boolean consistent() { return available + held + confirmed == total; }

        static Counts parse(String body) {
            return new Counts(num(body, "total_seats"), num(body, "available"),
                    num(body, "held"), num(body, "confirmed"));
        }

        private static int num(String body, String field) {
            Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(\\d+)").matcher(body);
            if (!m.find()) throw new IllegalStateException("field " + field + " missing in: " + body);
            return Integer.parseInt(m.group(1));
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final String base;

    public TestClient(String base) {
        this.base = base;
    }

    // ---- seat label helper -------------------------------------------------
    public static List<String> labels(String prefix, int from, int toInclusive) {
        List<String> out = new ArrayList<>();
        for (int i = from; i <= toInclusive; i++) out.add(prefix + i);
        return out;
    }

    // ---- business calls ----------------------------------------------------
    public String createShow(List<String> seats) {
        return createShow(seats, 25_000, 4);
    }

    public String createShow(List<String> seats, long pricePaise, int perUserLimit) {
        String body = "{\"name\":\"t\",\"seats\":[" + quoted(seats) + "],\"price_paise\":" + pricePaise
                + ",\"per_user_limit\":" + perUserLimit + "}";
        Result r = post("/shows", ADMIN, body);
        if (r.status() != 201) throw new IllegalStateException("createShow failed: " + r.status() + " " + r.body());
        return r.field("id");
    }

    public Result reserve(String user, String showId, List<String> seats, String key) {
        String body = "{\"seats\":[" + quoted(seats) + "],\"idempotency_key\":\"" + key + "\"}";
        return post("/shows/" + showId + "/reserve", user, body);
    }

    public Result reserveRaw(String user, String showId, String jsonBody, String... headerPairs) {
        return post("/shows/" + showId + "/reserve", user, jsonBody, headerPairs);
    }

    public Result cancel(String user, String reservationId) {
        return post("/reservations/" + reservationId + "/cancel", user, "");
    }

    public Result getShow(String showId) {
        return get("/shows/" + showId, "poller");
    }

    public Counts counts(String showId) {
        Result r = getShow(showId);
        if (r.status() != 200) throw new IllegalStateException("GET show failed: " + r.status() + " " + r.body());
        return Counts.parse(r.body());
    }

    // ---- plumbing ----------------------------------------------------------
    public Result post(String path, String token, String body, String... headerPairs) {
        HttpRequest.Builder b = builder(path, token)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i + 1 < headerPairs.length; i += 2) b.header(headerPairs[i], headerPairs[i + 1]);
        return send(b.build());
    }

    public Result get(String path, String token) {
        return send(builder(path, token).GET().build());
    }

    private HttpRequest.Builder builder(String path, String token) {
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(180))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token);
    }

    private Result send(HttpRequest request) {
        IN_FLIGHT.acquireUninterruptibly();
        try {
            for (int attempt = 1; ; attempt++) {
                try {
                    HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
                    return new Result(resp.statusCode(), resp.body());
                } catch (java.net.ConnectException e) {           // never reached the server: safe to retry
                    if (attempt >= 8) return new Result(-1, "connect failed after retries: " + e);
                    CONNECT_RETRIES.incrementAndGet();
                    try {
                        Thread.sleep(10L * attempt + ThreadLocalRandom.current().nextInt(20));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return new Result(-1, "interrupted");
                    }
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    return new Result(-1, "transport failure: " + e);
                }
            }
        } finally {
            IN_FLIGHT.release();
        }
    }

    private static String quoted(List<String> seats) {
        return seats.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(","));
    }
}
