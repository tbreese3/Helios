# Helios ♟️☀️  
*A blazing-fast, Java 24 chess engine focused on clean code, deep search, and easy extensibility.*

---

## Features
- **Iterative deepening + MTD(f)** core search with aspiration windows  
- **Bitboard move generation** using modern Java Vector API intrinsics  
- Adaptive **NNUE evaluation** fallback to handcrafted heuristics when the NN isn’t loaded  
- Built-in **JMH micro-benchmarks** (`./gradlew jmh`) and **JUnit 5 perft tests**  
- JSON UCI-style protocol over std in/out (trivial to plug into GUIs like Arena or CuteChess)

## Getting Started
```bash
# Clone and build
git clone https://github.com/your-handle/helios.git
cd helios
./gradlew build                       # compiles and runs all tests
./gradlew run -PstartUci              # launches the engine in UCI mode
```

## Project Layout
```
src/
 ├─ main/java/engine/        # core search, move gen, eval
 ├─ main/java/uci/           # UCI protocol I/O
 ├─ test/java/               # JUnit perft & regression suites
 └─ jmh/                     # micro-benchmarks
```

## License
Helios is released under the MIT License – see LICENSE for details.
