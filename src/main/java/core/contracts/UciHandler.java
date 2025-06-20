package core.contracts;

/**
 * Defines the contract for a class that handles the Universal Chess Interface (UCI)
 * command loop. An implementation of this interface is responsible for parsing
 * commands from a standard input stream (e.g., from a chess GUI) and
 * orchestrating the engine's response.
 */
public interface UciHandler {

    /**
     * Starts the main loop that continuously reads and processes UCI commands
     * until a "quit" command is received or the input stream is closed.
     */
    void runLoop();
}
