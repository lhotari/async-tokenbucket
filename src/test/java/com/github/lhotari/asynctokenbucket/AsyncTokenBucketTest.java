package com.github.lhotari.asynctokenbucket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AsyncTokenBucketTest {
    private final AtomicLong manualClockSource = new AtomicLong(TimeUnit.SECONDS.toNanos(100));
    private final LongSupplier clockSource = manualClockSource::get;

    private AsyncTokenBucket asyncTokenBucket;

    private void incrementSeconds(int seconds) {
        manualClockSource.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
        asyncTokenBucket.updateTokens();
    }

    private void incrementMillis(long millis) {
        manualClockSource.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
    }

    @Test
    void shouldAddTokensWithConfiguredRate() {
        asyncTokenBucket = new AsyncTokenBucket(100, 10, clockSource);
        incrementSeconds(5);
        assertEquals(50, asyncTokenBucket.tokens(true));
        incrementSeconds(1);
        assertEquals(60, asyncTokenBucket.tokens(true));
        incrementSeconds(4);
        assertEquals(100, asyncTokenBucket.tokens(true));

        // No matter how long the period is, tokens do not go above capacity
        incrementSeconds(5);
        assertEquals(100, asyncTokenBucket.tokens(true));

        // Consume all and verify none available and then wait 1 period and check replenished
        asyncTokenBucket.consumeTokens(100);
        assertEquals(0, asyncTokenBucket.tokens(true));
        incrementSeconds(1);
        assertEquals(10, asyncTokenBucket.tokens(true));
    }

    @Test
    void shouldCalculatePauseCorrectly() {
        asyncTokenBucket = new AsyncTokenBucket(100, 10, clockSource);
        incrementSeconds(5);
        asyncTokenBucket.consumeTokens(100);
        assertEquals(-50, asyncTokenBucket.tokens(true));
        assertEquals(6, TimeUnit.NANOSECONDS.toSeconds(asyncTokenBucket.calculatePauseNanos(10, true)));
    }

    @Test
    void shouldSupportFractionsWhenUpdatingTokens() {
        asyncTokenBucket = new AsyncTokenBucket(100, 10, clockSource);
        incrementMillis(100);
        assertEquals(1, asyncTokenBucket.tokens(true));
    }

    @Test
    void shouldSupportFractionsAndRetainLeftoverWhenUpdatingTokens() {
        asyncTokenBucket = new AsyncTokenBucket(100, 10, clockSource);
        for (int i = 0; i < 150; i++) {
            incrementMillis(1);
        }
        assertEquals(1, asyncTokenBucket.tokens(true));
        incrementMillis(150);
        assertEquals(3, asyncTokenBucket.tokens(true));
    }

    @Tag("performance")
    @ParameterizedTest
    @ValueSource(ints = {1, 10, 100})
    void shouldPerformanceOfConsumeTokensBeSufficient(int numberOfThreads) throws InterruptedException {
        long ratePerSecond = 100_000_000;
        int durationSeconds = 10;
        asyncTokenBucket = new AsyncTokenBucket(2 * ratePerSecond, ratePerSecond, System::nanoTime);
        Thread[] threads = new Thread[numberOfThreads];
        for (int i = 0; i < 2; i++) {
            waitUntilBucketIsFull();
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
            System.out.printf(Locale.US, "Achieved rate: %,d ops per second with %d threads%n", totalCount / durationSeconds,
                    numberOfThreads);
        }
    }

    private void waitUntilBucketIsFull() throws InterruptedException {
        pause(asyncTokenBucket, asyncTokenBucket.getCapacity());
    }

    private static void pause(AsyncTokenBucket asyncTokenBucket, long minTokens) throws InterruptedException {
        long pauseMillis = TimeUnit.NANOSECONDS.toMillis(asyncTokenBucket.calculatePauseNanos(minTokens, true));
        if (pauseMillis > 0) {
            Thread.sleep(pauseMillis);
        }
    }

}
