package main;

import core.contracts.*;
import core.impl.*;

/**
 * Main entry point for the Helios chess engine command-line interface.
 * Initializes all necessary engine components and starts the UCI command loop.
 */
public class Main {
    public static void main(String[] args) {
        System.out.println("Helios Chess Engine");

        // --- Engine Component Initialization ---
        PositionFactory positionFactory = new PositionFactoryImpl();
        MoveGenerator moveGenerator = new MoveGeneratorImpl();
        Evaluator evaluator = new EvaluatorImpl();
        TranspositionTable transpositionTable = new TranspositionTableImpl(64);
        TimeManager timeManager = new TimeManagerImpl();
        SearchWorkerFactory workerFactory = (isMain, pool) -> new SearchWorkerImpl(isMain, pool);
        WorkerPool workerPool = new WorkerPoolImpl(1, workerFactory);

        Search search = new SearchImpl(positionFactory, moveGenerator, evaluator, workerPool, timeManager);
        search.setTranspositionTable(transpositionTable);

        // --- UCI Handler Initialization ---
        UciOptions uciOptions = new UciOptionsImpl(search, transpositionTable);
        UciHandler uciHandler = new UciHandlerImpl(search, positionFactory, uciOptions);

        try {
            uciHandler.runLoop();
        } catch (Exception e) {
            System.err.println("A critical error occurred in the UCI loop: " + e.getMessage());
            e.printStackTrace();
        } finally {
            search.close();
        }
    }
}