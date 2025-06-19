package core.contracts;

/**
 * A factory for creating instances of {@link SearchWorker}.
 *
 * <p>This abstraction allows the {@link WorkerPool} to create search workers
 * without being coupled to a specific implementation.</p>
 */
public interface SearchWorkerFactory {

    /**
     * Creates a new search worker.
     *
     * @param isMainThread Whether this worker will be the main one,
     * responsible for driving iterative deepening.
     * @param pool         The worker pool this worker will belong to.
     * @return A new instance of a {@link SearchWorker}.
     */
    SearchWorker create(boolean isMainThread, WorkerPool pool);
}