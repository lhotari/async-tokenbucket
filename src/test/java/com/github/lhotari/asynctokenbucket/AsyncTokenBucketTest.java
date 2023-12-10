/*
 * Copyright (C) 2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.lhotari.asynctokenbucket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AsyncTokenBucketTest {
    private final AtomicLong manualClockSource = new AtomicLong(TimeUnit.SECONDS.toNanos(100));
    private final AsyncTokenBucket.MonotonicClockSource clockSource = highPrecision -> manualClockSource.get();

    private AsyncTokenBucket asyncTokenBucket;

    private void incrementSeconds(int seconds) {
        manualClockSource.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
    }

    private void incrementMillis(long millis) {
        manualClockSource.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
    }

    @Test
    void shouldAddTokensWithConfiguredRate() {
        asyncTokenBucket =
                AsyncTokenBucket.builder().capacity(100).rate(10).initialTokens(0).clockSource(clockSource).build();
        incrementSeconds(5);
        assertEquals(asyncTokenBucket.getTokens(), 50);
        incrementSeconds(1);
        assertEquals(asyncTokenBucket.getTokens(), 60);
        incrementSeconds(4);
        assertEquals(asyncTokenBucket.getTokens(), 100);

        // No matter how long the period is, tokens do not go above capacity
        incrementSeconds(5);
        assertEquals(asyncTokenBucket.getTokens(), 100);

        // Consume all and verify none available and then wait 1 period and check replenished
        asyncTokenBucket.consumeTokens(100);
        assertEquals(asyncTokenBucket.tokens(true), 0);
        incrementSeconds(1);
        assertEquals(asyncTokenBucket.getTokens(), 10);
    }

    @Test
    void shouldCalculatePauseCorrectly() {
        asyncTokenBucket =
                AsyncTokenBucket.builder().capacity(100).rate(10).initialTokens(0).clockSource(clockSource)
                        .build();
        incrementSeconds(5);
        asyncTokenBucket.consumeTokens(100);
        assertEquals(asyncTokenBucket.getTokens(), -50);
        assertEquals(TimeUnit.NANOSECONDS.toMillis(asyncTokenBucket.calculateThrottlingDuration()), 5100);
    }

    @Test
    void shouldSupportFractionsWhenUpdatingTokens() {
        asyncTokenBucket =
                AsyncTokenBucket.builder().capacity(100).rate(10).initialTokens(0).clockSource(clockSource).build();
        incrementMillis(100);
        assertEquals(asyncTokenBucket.getTokens(), 1);
    }

    @Test
    void shouldSupportFractionsAndRetainLeftoverWhenUpdatingTokens() {
        asyncTokenBucket =
                AsyncTokenBucket.builder().capacity(100).rate(10).initialTokens(0).clockSource(clockSource).build();
        for (int i = 0; i < 150; i++) {
            incrementMillis(1);
        }
        assertEquals(asyncTokenBucket.getTokens(), 1);
        incrementMillis(150);
        assertEquals(asyncTokenBucket.getTokens(), 3);
    }
}
