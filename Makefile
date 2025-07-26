# ─────────────  Helios Makefile (pure cmd.exe)  ─────────────
SHELL          := cmd.exe
.SHELLFLAGS    := /C

APP_NAME       := Helios
GRADLE         := gradlew.bat            # wrapper in the repo root
EXE           ?= $(APP_NAME).exe         # OpenBench sets EXE=Helios‑<sha>

# Output of the Gradle warpPack task
PACKED         := build\dist\Helios.exe

.PHONY: all clean

# ---------------------------------------------------------------------------
all: $(EXE)
# ---------------------------------------------------------------------------

# 1) Build the single‑file executable via Gradle
$(PACKED):
	@echo Building with Gradle …
	@"$(GRADLE)" --no-daemon --console=plain warpPack

# 2) Copy / rename for OpenBench (handles names with or without ".exe")
$(EXE): $(PACKED)
	@echo Copying to $(EXE) …
	@if not exist "$(dir $(EXE))" mkdir "$(dir $(EXE))"
	@copy /Y "$(PACKED)" "$(EXE)"        >nul
	@copy /Y "$(PACKED)" "$(EXE).exe"  >nul 2>nul
	@echo Done.

# 3) House‑keeping
clean:
	@echo Cleaning …
	-@"$(GRADLE)" --no-daemon clean
	-@del /Q "$(PACKED)"        2>nul
	-@del /Q "$(EXE)"           2>nul
	-@del /Q "$(EXE).exe"       2>nul
