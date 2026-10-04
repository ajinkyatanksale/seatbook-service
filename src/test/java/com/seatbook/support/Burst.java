package com.seatbook.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

public final class Burst {

    private Burst() {}

    /**
     * Runs task(0..n-1) so that every task starts at the same instant (start gate), and
     * returns the results in index order, so results.get(i) belongs to task i.
     */
    public static <T> List<T> run(int n, IntFunction<T> task) {
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                final int idx = i;
                futures.add(ex.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.apply(idx);
                }));
            }
            ready.await();
            go.countDown();
            List<T> out = new ArrayList<>(n);
            for (Future<T> f : futures) out.add(f.get());
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    public static long count(List<Result> rs, int status) {
        return rs.stream().filter(r -> r.status() == status).count();
    }

    public static long count(List<Result> rs, int status, String error) {
        return rs.stream().filter(r -> r.status() == status && Objects.equals(error, r.error())).count();
    }

    /** e.g. {201=1, 409:seat_taken=499} */
    public static Map<String, Long> tally(List<Result> rs) {
        Map<String, Long> m = new TreeMap<>();
        for (Result r : rs) {
            String key = r.status() + (r.error() == null ? "" : ":" + r.error());
            m.merge(key, 1L, Long::sum);
        }
        return m;
    }

    public static void assertNoServerErrors(List<Result> rs) {
        List<String> bad = rs.stream().filter(Result::serverError)
                .map(r -> r.status() + " " + r.body()).limit(3).toList();
        assertThat(bad).as("5xx / transport failures; tally=" + tally(rs)).isEmpty();
    }

    /** Every outcome must be one of the allowed "status" or "status:error" keys. */
    public static void assertOnly(List<Result> rs, Set<String> allowed) {
        assertThat(tally(rs).keySet()).as("unexpected outcome in " + tally(rs)).isSubsetOf(allowed);
    }
}
