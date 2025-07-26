# ───────── Helios Makefile (Windows‑only) ─────────
# Force every recipe to run in cmd.exe
SHELL        := cmd.exe
.SHELLFLAGS  := /C

APP_NAME     := Helios
GRADLE       := gradlew.bat
PACKED       := build\dist\Helios.exe      # output of warpPack

# OpenBench sets EXE=Helios‑<sha>[.exe]
EXE         ?= $(APP_NAME).exe

# --------------------------------------------------------------------------- #
.PHONY: all build clean
# Default target OpenBench calls
all: build
# --------------------------------------------------------------------------- #

# ---------- build & copy in one recipe ------------------------------------- #
build:
	@echo === 1. Build with Gradle ===========================================
	@call "$(GRADLE)" --no-daemon --console=plain warpPack

	@echo === 2. Copy to $(EXE) =============================================
	@if not exist "$(dir $(EXE))" mkdir "$(dir $(EXE))"
	@copy /Y "$(PACKED)" "$(EXE)" >nul
	@rem If caller omitted ".exe", also drop the .exe twin
	@if /I not "$(suffix $(EXE))"==".exe" copy /Y "$(PACKED)" "$(EXE).exe" >nul
	@echo Done.

# ---------- clean ---------------------------------------------------------- #
clean:
	@echo Cleaning …
	-@call "$(GRADLE)" --no-daemon clean
	-@if exist "$(PACKED)"     del /Q "$(PACKED)"
	-@if exist "$(EXE)"        del /Q "$(EXE)"
	-@if exist "$(EXE).exe"    del /Q "$(EXE).exe"
