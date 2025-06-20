package core.contracts;

/**
 * Defines the contract for a class that manages UCI (Universal Chess Interface)
 * options for the engine. An implementation of this interface is responsible for
 * parsing and applying option settings received from a UCI-compatible GUI.
 */
public interface UciOptions {

    /**
     * Parses a "setoption" command line and applies the given value
     * to the corresponding engine parameter.
     *
     * @param line The full "setoption ..." command line from the GUI.
     */
    void setOption(String line);

    /**
     * Prints all available UCI options to standard output in the format
     * required by the UCI protocol. This is typically sent in response
     * to the "uci" command.
     */
    void printOptions();

    /**
     * Provides access to the transposition table, allowing options
     * (like changing its size) to be applied directly.
     *
     * @return The TranspositionTable instance managed by the options handler.
     */
    TranspositionTable getTranspositionTable();

    String getOptionValue(String name);

    void attachSearch(Search s);
}