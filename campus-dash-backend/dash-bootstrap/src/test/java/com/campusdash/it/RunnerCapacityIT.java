package com.campusdash.it;

import com.campusdash.domain.runner.ports.RunnerWorkloadRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"dash.mq.enabled=false"})
class RunnerCapacityIT {
    private static final long RUNNER = 990_902L;

    @Autowired RunnerWorkloadRepository workloads;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void requireMiddleware() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(), "中间件未启动，跳过");
    }

    @BeforeEach
    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM runner_capacity_assignment WHERE runner_id=?", RUNNER);
        jdbc.update("DELETE FROM runner_workload WHERE runner_id=?", RUNNER);
    }

    @Test
    void existing_row_adopts_new_configured_limit() {
        jdbc.update("INSERT INTO runner_workload(runner_id,ongoing_count,max_ongoing,version) VALUES (?,5,5,0)", RUNNER);

        assertTrue(workloads.tryAcquire(RUNNER, 1000));
        assertEquals(6, workloads.ongoingCount(RUNNER));
        assertEquals(1000, jdbc.queryForObject(
                "SELECT max_ongoing FROM runner_workload WHERE runner_id=?", Integer.class, RUNNER));
    }

    @Test
    void concurrent_acquire_never_exceeds_configured_limit() throws Exception {
        int attempts = 10;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return workloads.tryAcquire(RUNNER, 3);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int successes = 0;
            for (Future<Boolean> future : futures) if (future.get(5, TimeUnit.SECONDS)) successes++;

            assertEquals(3, successes);
            assertEquals(3, workloads.ongoingCount(RUNNER));
        } finally {
            pool.shutdownNow();
        }
    }
}
