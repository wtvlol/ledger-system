package com.example.ledger.concurrency;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.example.ledger.domain.LedgerException;

/**
 * Serializes bounded write admission and resolves accepted requests during shutdown.
 */
public final class WriteQueue implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final Duration shutdownTimeout;
    private volatile Work<?> running;

    /**
     * Creates a FIFO writer with a bounded capacity and shutdown drain deadline.
     *
     * @param capacity Maximum number of waiting operations, excluding the active worker.
     * @param shutdownTimeout Drain deadline before canceling pending work and interrupting the active
     *     operation.
     * @throws IllegalArgumentException if the waiting capacity is not positive.
     */
    public WriteQueue(int capacity, Duration shutdownTimeout) {
        this.shutdownTimeout = shutdownTimeout;
        executor =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(capacity),
                        task -> {
                            Thread thread = new Thread(task, "ledger-writer");
                            thread.setDaemon(false);
                            return thread;
                        });
    }

    /**
     * Admits an operation to the single writer or completes it with a retryable rejection.
     *
     * @param <T> Type of the operation's returned result.
     * @param action Operation to execute on the queue worker or within the specified transaction.
     * @return Future resolving after worker execution or failing with a retryable admission rejection.
     */
    public <T> CompletableFuture<T> submit(Callable<T> action) {
        Work<T> work = new Work<>(action);
        try {
            executor.execute(work);
        } catch (RejectedExecutionException e) {
            work.result.completeExceptionally(
                    LedgerException.createRetry(
                            "QUEUE_UNAVAILABLE",
                            "Writer queue is full or shutting down; retry with the same key",
                            "NOT_POSTED"));
        }
        return work.result;
    }

    /**
     * Completes one admitted operation's future after its execution finishes.
     */
    private final class Work<T> implements Runnable {
        private final Callable<T> action;
        private final CompletableFuture<T> result = new CompletableFuture<>();

        /**
         * Associates one admitted operation with its completion future.
         *
         * @param action Operation to execute on the queue worker or within the specified transaction.
         */
        private Work(Callable<T> action) {
            this.action = action;
        }

        /**
         * Executes the admitted operation and completes its future with the result or failure.
         */
        @Override
        public void run() {
            running = this;
            try {
                result.complete(action.call());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            } finally {
                running = null;
            }
        }
    }

    /**
     * Stops admissions, drains accepted work, and retains ownership until the active transaction finishes.
     */
    @Override
    public void close() {
        executor.shutdown();
        boolean isInterrupted = false;
        try {
            if (!executor.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                stopPendingWork();
            }
        } catch (InterruptedException e) {
            isInterrupted = true;
            stopPendingWork();
        }
        // Keep database ownership until the in-flight transaction commits or rolls back.
        while (!executor.isTerminated()) {
            try {
                executor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                isInterrupted = true;
                stopPendingWork();
            }
        }
        if (isInterrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Cancels never-started work and reports an unknown outcome for an interrupted active operation.
     */
    private void stopPendingWork() {
        for (Runnable pending : executor.shutdownNow()) {
            ((Work<?>) pending)
                    .result.completeExceptionally(
                            LedgerException.createRetry(
                                    "SHUTDOWN",
                                    "Request never started; retry with its original key",
                                    "NOT_POSTED"));
        }
        Work<?> active = running;
        if (active != null) {
            active.result.completeExceptionally(
                    LedgerException.createRetry(
                            "SHUTDOWN",
                            "In-flight outcome is unknown; retry with its original key",
                            "UNKNOWN"));
        }
    }
}
