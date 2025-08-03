# Helios ♟️☀️  
---

## Features
- **Iterative deepening + MTD(f)** core search with aspiration windows  
- **Bitboard move generation** using modern Java Vector API intrinsics  
- Adaptive **NNUE evaluation** fallback to handcrafted heuristics when the NN isn’t loaded  

## Getting Started
```bash
# Clone and build
git clone https://github.com/your-handle/helios.git
cd helios
buildandrun.bat Helios
```


## Development environment

| Tool | Version | Notes |
|------|---------|-------|
| **JDK** | 24 (Temurin or Zulu) | Required for Vector API & preview features |
| **Gradle** | Wrapper ships with the repo | Just run `./gradlew …` |
| **IDE – recommended** | [IntelliJ IDEA 2025.1](https://www.jetbrains.com/idea/) (Community or Ultimate) | Import as **Gradle**; run configs are pre-bundled. |

After cloning, open IntelliJ → File ▸ New ▸ Project from Existing Sources → select the repo root → choose Gradle.

```

## License
Helios is released under the MIT License – see the [LICENSE](LICENSE) file for full text.
