package core.contracts;

import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Thin façade over an executor / work-stealing pool.
 * <p>You can start with {@code Executors.newFixedThreadPool(…)} behind it
 * and later swap in a specialised split-point pool without touching code in
 * {@code core.search}.</p>
 */
public interface TaskPool extends AutoCloseable {

    /** Submit a unit of work and get a {@link Future} for its result. */
    <T> Future<T> submit(Callable<T> task);

    /** Asynchronously execute <em>fire-and-forget</em> work. */
    default void execute(Runnable task) { submit(Executors.callable(task)); }

    /** Hint a new parallelism level (may be ignored by the pool). */
    default void setParallelism(int threads) {}

    /** Cancel all queued/running tasks immediately. */
    void shutdownNow();

    /** Identical to {@link #shutdownNow()}. */
    @Override default void close() { shutdownNow(); }
}
