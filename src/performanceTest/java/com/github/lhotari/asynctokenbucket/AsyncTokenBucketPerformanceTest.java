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

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AsyncTokenBucketPerformanceTest {
    private AsyncTokenBucket asyncTokenBucket;

    @ParameterizedTest
    @ValueSource(ints = {1, 10, 100})
    void shouldPerformanceOfConsumeTokensBeSufficient(int numberOfThreads) throws InterruptedException {
        try (AsyncTokenBucket.GranularMonotonicClockSource clockSource =
                     new AsyncTokenBucket.GranularMonotonicClockSource(TimeUnit.MILLISECONDS.toNanos(8),
                             System::nanoTime)) {
            long ratePerSecond = 100_000_000;
            int durationSeconds = 10;
            Thread[] threads = new Thread[numberOfThreads];
            for (int i = 0; i < 2; i++) {
                asyncTokenBucket = AsyncTokenBucket.builder()
                        .rate(ratePerSecond)
                        .clockSource(clockSource)
                        .initialTokens(2 * ratePerSecond)
                        .capacity(2 * ratePerSecond)
                        .build();
                long startNanos = System.nanoTime();
                long endNanos = startNanos + TimeUnit.SECONDS.toNanos(durationSeconds);
                AtomicLong totalCounter = new AtomicLong();
                System.out.printf("Consuming for %d seconds...%n", durationSeconds);
                for (int t = 0; t < numberOfThreads; t++) {
                    Thread thread = new Thread(() -> {
                        long counter = 0;
                        while (clockSource.getNanos(false) < endNanos) {
                            asyncTokenBucket.consumeTokens(1);
                            counter++;
                        }
                        totalCounter.addAndGet(counter);
                    });
                    threads[t] = thread;
                    thread.start();
                }
                for (int t = 0; t < numberOfThreads; t++) {
                    threads[t].join();
                }
                long totalCount = totalCounter.get();
                System.out.println("Counter value " + totalCount + " tokens:" + asyncTokenBucket.tokens(true));
                System.out.printf(Locale.US, "Achieved rate: %,d ops per second with %d threads%n",
                        totalCount / durationSeconds,
                        numberOfThreads);
            }
        }
    }
}
