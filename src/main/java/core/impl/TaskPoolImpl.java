package core.concurrent;

import core.contracts.TaskPool;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;

/**
 * Default {@link TaskPool} backed by a work‑stealing {@link ForkJoinPool}.
 * <p>
 * ✓  Re‑entrant (tasks may spawn more tasks inside worker threads).
 * <br>✓  Parallelism can be adjusted on the fly via {@link #setParallelism}.
 * <br>✓  Supports {@link #shutdownNow()} for hard cancellation.
 * </p>
 * <p>
 * <strong>Usage:</strong>
 * <pre>
 * TaskPool pool = new DefaultTaskPool();          // default = #cores
 * Future<Foo> f = pool.submit(this::computeFoo);  // any Callable / Runnable
 * Foo result = f.get();                           // may throw InterruptedException
 * pool.close();                                   // shuts down workers
 * </pre>
 * The implementation purposely keeps <em>no</em> other public surface so the
 * engine can swap in a specialised split‑point pool later without touching
 * search code.
 */
public final class TaskPoolImpl implements TaskPool {

    /* ---------------- internal state ---------------- */

    /** Guard for pool replacement / shutdown. */
    private final Object lifecycleLock = new Object();

    /** The live worker pool; {@code null} once {@link #shutdownNow()} ran. */
    private volatile ForkJoinPool pool;

    /**
     * @param parallelism desired number of worker threads (≥ 1).
     */
    public TaskPoolImpl(int parallelism) {
        this.pool = newForkJoin(parallelism);
    }

    /* ---------------- TaskPool API ------------------ */

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        ForkJoinPool p = pool;
        if (p == null) {
            throw new IllegalStateException("TaskPool is shut down");
        }
        return p.submit(task);      // ForkJoinTask implements Future!
    }

    @Override
    public void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        ForkJoinPool p = pool;
        if (p == null) {
            throw new IllegalStateException("TaskPool is shut down");
        }
        p.execute(task);
    }

    @Override
    public void setParallelism(int threads) {
        if (threads < 1) return;              // ignore invalid requests
        synchronized (lifecycleLock) {
            ForkJoinPool current = pool;
            if (current == null) return;      // already shut down
            if (current.getParallelism() == threads) return; // no change

            // Replace the pool: new → field swap → old.shutdown()
            ForkJoinPool replacement = newForkJoin(threads);
            pool = replacement;               // publish first – submitters will see it
            current.shutdown();               // graceful » let running tasks finish
        }
    }

    @Override
    public void shutdownNow() {
        synchronized (lifecycleLock) {
            ForkJoinPool current = pool;
            pool = null;                      // mark closed – future submits will fail
            if (current != null) {
                current.shutdownNow();        // interrupt idle workers, cancel queuing
            }
        }
    }

    /* ---------------- helpers ----------------------- */

    /** Build a work‑stealing {@link ForkJoinPool} with async‑mode <em>enabled</em>. */
    private static ForkJoinPool newForkJoin(int parallelism) {
        return new ForkJoinPool(
                parallelism,
                ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                /* handler */ null,
                /* asyncMode */ true);
    }
}
