# ───────────── Helios Makefile (Windows) ─────────────
SHELL        := cmd
.SHELLFLAGS  := /C

APP_NAME     := Helios
GRADLE       := gradlew.bat
PACKED       := build\dist\Helios.exe     # output of Gradle warpPack

# OpenBench passes EXE=Helios‑<sha>[.exe]  — default for local tests:
EXE         ?= $(APP_NAME).exe

# ---------------------------------------------------------------------
.PHONY: all
all: build-and-copy
# ---------------------------------------------------------------------

.PHONY: build-and-copy
build-and-copy:
	@echo === 1. Build single‑file exe with Gradle =========
	@"$(GRADLE)" --no-daemon --console=plain warpPack

	@echo === 2. Copy/rename for OpenBench =================
	@if not exist "$(dir $(EXE))" mkdir "$(dir $(EXE))"
	@copy /Y "$(PACKED)" "$(EXE)"    >nul
	@rem If caller omitted .exe, also drop Helios‑<sha>.exe
	@if not exist "$(EXE).exe" copy /Y "$(PACKED)" "$(EXE).exe" >nul 2>nul
	@echo Done.

# ---------------------------------------------------------------------
.PHONY: clean
clean:
	@echo Cleaning …
	-@"$(GRADLE)" --no-daemon clean
	-@del /Q "$(PACKED)"        2>nul
	-@del /Q "$(EXE)"           2>nul
	-@del /Q "$(EXE).exe"       2>nul
