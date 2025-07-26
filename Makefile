# ───────── Helios Makefile (minimal Windows) ─────────
SHELL        := cmd.exe
.SHELLFLAGS  := /C

APP_NAME     := Helios
GRADLE       := gradlew.bat
PACKED       := build\dist\Helios.exe     # produced by warpPack

# OpenBench calls:  make EXE=Helios‑<sha>[.exe]
EXE         ?= $(APP_NAME).exe

# ---------- default target --------------------------------------------------
.PHONY: all
all: $(PACKED)
	@echo Copying to $(EXE) …
	@if not exist "$(dir $(EXE))" mkdir "$(dir $(EXE))"
	@copy /Y "$(PACKED)" "$(EXE)" >nul
	@rem If EXE lacks .exe, also drop the .exe twin
	@if /I not "$(suffix $(EXE))"==".exe" copy /Y "$(PACKED)" "$(EXE).exe" >nul

# ---------- build the single‑file exe ---------------------------------------
$(PACKED):
	@echo Building with Gradle …
	@"$(GRADLE)" --no-daemon --console=plain warpPack

# ---------- clean -----------------------------------------------------------
.PHONY: clean
clean:
	-@"$(GRADLE)" --no-daemon clean
	-@del /Q "$(PACKED)"       2>nul
	-@del /Q "$(EXE)"          2>nul
	-@del /Q "$(EXE).exe"      2>nul
