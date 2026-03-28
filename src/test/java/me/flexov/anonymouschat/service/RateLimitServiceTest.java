package me.flexov.anonymouschat.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitServiceTest {

    @Test
    void tryAcquireBlocksRequestsAfterLimitAndAllowsAfterWindow() {
        AtomicLong now = new AtomicLong(0L);
        RateLimitService rateLimitService = new RateLimitService(now::get);

        assertTrue(rateLimitService.tryAcquire("room", "client-1", 2, Duration.ofSeconds(10)));
        assertTrue(rateLimitService.tryAcquire("room", "client-1", 2, Duration.ofSeconds(10)));
        assertFalse(rateLimitService.tryAcquire("room", "client-1", 2, Duration.ofSeconds(10)));

        now.set(10_001L);

        assertTrue(rateLimitService.tryAcquire("room", "client-1", 2, Duration.ofSeconds(10)));
    }
}
