package com.campusdash.bench;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P9 同步/异步对照压测。使用测试身份头，运行时需显式开启 allow-header-identity。
 *
 * <pre>
 * java ... RushLoadClient --mode async --requests 3000 --quota 500 --concurrency 3000
 * java ... RushLoadClient --mode sync  --requests 3000 --concurrency 3000
 * </pre>
 */
public final class RushLoadClient {
    private RushLoadClient() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        String base = options.getOrDefault("base", "http://127.0.0.1:8080");
        String mode = options.getOrDefault("mode", "async");
        int requests = Integer.parseInt(options.getOrDefault("requests", "3000"));
        int concurrency = Integer.parseInt(options.getOrDefault("concurrency", String.valueOf(requests)));
        long campaignId = Long.parseLong(options.getOrDefault("campaign", "90001"));

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        if (requests <= 0 || concurrency <= 0) {
            throw new IllegalArgumentException("requests 与 concurrency 必须大于 0");
        }
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        Semaphore permits = new Semaphore(Math.min(concurrency, requests));
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch fire = new CountDownLatch(1);
        List<Long> acceptLatencies = Collections.synchronizedList(new ArrayList<>());
        List<Long> finalLatencies = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger success = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        AtomicInteger duplicate = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 1; i <= requests; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                long userId = 1_000_000L + index;
                String requestId = "rush:" + campaignId + ":load:" + index + ":" + UUID.randomUUID();
                ready.countDown();
                try {
                    fire.await();
                    permits.acquire();
                    try {
                        long started = System.nanoTime();
                        HttpResponse<String> response = client.send(buildRequest(base, mode, campaignId,
                                        userId, requestId, index), HttpResponse.BodyHandlers.ofString());
                        acceptLatencies.add(elapsedMs(started));
                        if (!response.body().contains("\"code\":\"OK\"")) {
                            classify(response.body(), soldOut, duplicate, insufficient, errors);
                            return;
                        }
                        accepted.incrementAndGet();
                        if ("sync".equals(mode)) {
                            success.incrementAndGet();
                            finalLatencies.add(elapsedMs(started));
                            return;
                        }
                        poll(client, base, userId, requestId, started,
                                finalLatencies, success, soldOut, duplicate, insufficient, errors);
                    } finally {
                        permits.release();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            }));
        }
        ready.await(60, TimeUnit.SECONDS);
        long wallStart = System.nanoTime();
        fire.countDown();
        for (Future<?> future : futures) future.get(180, TimeUnit.SECONDS);
        pool.shutdown();

        System.out.println("================ P9 Rush Load ================");
        System.out.printf("mode=%s requests=%d concurrency=%d%n", mode, requests, concurrency);
        System.out.printf("accepted=%d success=%d soldOut=%d duplicate=%d insufficient=%d errors=%d%n",
                accepted.get(), success.get(), soldOut.get(), duplicate.get(), insufficient.get(), errors.get());
        printLatency("API受理", acceptLatencies);
        printLatency("最终完成", finalLatencies);
        System.out.printf("wallMs=%d%n", elapsedMs(wallStart));
        System.out.println("数据库正确性请继续执行 bench/scripts/verify_rush.sql");
    }

    private static HttpRequest buildRequest(String base, String mode, long campaignId,
                                            long userId, String requestId, int index) {
        String path = "async".equals(mode)
                ? "/api/rush-campaigns/" + campaignId + "/orders" : "/api/errands";
        String body = "async".equals(mode)
                ? "{\"title\":\"rush_load_" + index + "\",\"rewardCents\":100}"
                : "{\"campusId\":1,\"type\":\"BUY\",\"title\":\"sync_load_" + index
                  + "\",\"rewardCents\":100,\"slotTotal\":1}";
        return HttpRequest.newBuilder(URI.create(base + path))
                .header("X-User-Id", String.valueOf(userId))
                .header("X-Request-Id", requestId)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private static void poll(HttpClient client, String base, long userId, String requestId, long started,
                             List<Long> finalLatencies, AtomicInteger success, AtomicInteger soldOut,
                             AtomicInteger duplicate, AtomicInteger insufficient, AtomicInteger errors)
            throws Exception {
        for (int i = 0; i < 120; i++) {
            Thread.sleep(250);
            HttpRequest query = HttpRequest.newBuilder(
                            URI.create(base + "/api/rush-requests/" + requestId))
                    .header("X-User-Id", String.valueOf(userId)).GET().build();
            String body = client.send(query, HttpResponse.BodyHandlers.ofString()).body();
            if (body.contains("\"status\":\"PROCESSING\"") || body.contains("RUSH_REQUEST_NOT_FOUND")) continue;
            finalLatencies.add(elapsedMs(started));
            if (body.contains("\"status\":\"SUCCESS\"")) success.incrementAndGet();
            else classify(body, soldOut, duplicate, insufficient, errors);
            return;
        }
        errors.incrementAndGet();
    }

    static String classifyCode(String body) {
        if (body.contains("RUSH_SOLD_OUT")) return "SOLD_OUT";
        if (body.contains("RUSH_DUPLICATE_USER")) return "DUPLICATE";
        if (body.contains("INSUFFICIENT_BALANCE")) return "INSUFFICIENT";
        return "ERROR";
    }

    private static void classify(String body, AtomicInteger soldOut, AtomicInteger duplicate,
                                 AtomicInteger insufficient, AtomicInteger errors) {
        switch (classifyCode(body)) {
            case "SOLD_OUT" -> soldOut.incrementAndGet();
            case "DUPLICATE" -> duplicate.incrementAndGet();
            case "INSUFFICIENT" -> insufficient.incrementAndGet();
            default -> errors.incrementAndGet();
        }
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) result.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        return result;
    }

    private static long elapsedMs(long started) { return (System.nanoTime() - started) / 1_000_000; }

    private static void printLatency(String label, List<Long> values) {
        if (values.isEmpty()) return;
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        System.out.printf("%s P50/P95/P99/Max=%d/%d/%d/%dms%n", label,
                percentile(sorted, 50), percentile(sorted, 95), percentile(sorted, 99), sorted.getLast());
    }

    private static long percentile(List<Long> sorted, int p) {
        int index = Math.max(0, (int) Math.ceil(sorted.size() * p / 100.0) - 1);
        return sorted.get(index);
    }
}
