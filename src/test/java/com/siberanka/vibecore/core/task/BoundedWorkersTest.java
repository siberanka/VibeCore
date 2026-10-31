package com.siberanka.vibecore.core.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class BoundedWorkersTest {

    @Test
    void refusesWorkBeyondCapacity() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        try (BoundedWorkers workers = new BoundedWorkers("test", 1, 1, Logger.getAnonymousLogger())) {
            var first = workers.trySubmit(() -> {
                running.countDown();
                release.await();
                return 1;
            });
            assertTrue(running.await(5, TimeUnit.SECONDS));
            var queued = workers.trySubmit(() -> 2);
            var refused = workers.trySubmit(() -> 3);

            assertTrue(first.isPresent());
            assertTrue(queued.isPresent());
            assertTrue(refused.isEmpty(), "work beyond the bounded queue must be refused");

            release.countDown();
            assertEquals(1, first.get().get(5, TimeUnit.SECONDS));
            assertEquals(2, queued.get().get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void refusesWorkAfterClose() {
        BoundedWorkers workers = new BoundedWorkers("test", 1, 4, Logger.getAnonymousLogger());
        workers.close();
        assertTrue(workers.trySubmit(() -> 1).isEmpty());
    }
}
