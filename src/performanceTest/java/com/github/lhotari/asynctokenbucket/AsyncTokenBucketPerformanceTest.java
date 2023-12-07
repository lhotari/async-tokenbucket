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
        long ratePerSecond = 100_000_000;
        int durationSeconds = 10;
        Thread[] threads = new Thread[numberOfThreads];
        for (int i = 0; i < 2; i++) {
            asyncTokenBucket = AsyncTokenBucket.builder()
                    .rate(ratePerSecond)
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
                    while (System.nanoTime() < endNanos) {
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
