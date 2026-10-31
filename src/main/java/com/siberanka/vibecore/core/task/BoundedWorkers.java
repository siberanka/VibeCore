package com.siberanka.vibecore.core.task;

import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fixed-size worker pool with a bounded queue for file, media and pack work.
 * Work beyond the queue capacity is refused instead of growing memory without
 * limit; callers report the refusal to the requester.
 */
public final class BoundedWorkers implements AutoCloseable {

    private final ThreadPoolExecutor executor;
    private final int queueCapacity;

    public BoundedWorkers(String name, int threads, int queueCapacity, Logger logger) {
        if (threads < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("threads and queueCapacity must be positive");
        }
        this.queueCapacity = queueCapacity;
        AtomicInteger counter = new AtomicInteger();
        executor = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), runnable -> {
                    Thread thread = new Thread(runnable, name + "-worker-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    thread.setUncaughtExceptionHandler((failed, error) ->
                            logger.log(Level.SEVERE, "Uncaught error in " + failed.getName(), error));
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
    }

    /**
     * Queues work when capacity allows.
     *
     * @return the pending result, or empty when the queue is full or closed
     */
    public <T> Optional<CompletableFuture<T>> trySubmit(Callable<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                if (future.isCancelled()) {
                    return;
                }
                try {
                    future.complete(work.call());
                } catch (Throwable failure) {
                    future.completeExceptionally(failure);
                }
            });
            return Optional.of(future);
        } catch (RejectedExecutionException rejected) {
            return Optional.empty();
        }
    }

    public int queued() {
        return executor.getQueue().size();
    }

    public int active() {
        return executor.getActiveCount();
    }

    public int queueCapacity() {
        return queueCapacity;
    }

    public int threads() {
        return executor.getMaximumPoolSize();
    }

    /** Stops accepting work, waits briefly for running work and abandons the queue. */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
