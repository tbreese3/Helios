# Helios ♟️☀️  
*A fast, Java 24 chess engine focused on deep search and easy extensibility.*

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

## Development environment

| Tool | Version | Notes |
|------|---------|-------|
| **JDK** | 24 (Temurin or Zulu) | Required for Vector API & preview features |
| **Gradle** | Wrapper ships with the repo | Just run `./gradlew …` |
| **IDE – recommended** | [IntelliJ IDEA 2025.1](https://www.jetbrains.com/idea/) (Community or Ultimate) | Import as **Gradle**; run configs are pre-bundled. |

After cloning, open IntelliJ → File ▸ New ▸ Project from Existing Sources → select the repo root → choose Gradle.

## Project Layout
```
src/
 ├─ main/java/engine/        # core search, move gen, eval
 ├─ main/java/uci/           # UCI protocol I/O
 ├─ test/java/               # JUnit perft & regression suites
 └─ jmh/                     # micro-benchmarks
```

## License
Helios is released under the MIT License – see the [LICENSE](LICENSE) file for full text.
