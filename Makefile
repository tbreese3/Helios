# ─────────────  Helios Makefile (Windows)  ─────────────
# Force every recipe to run in cmd.exe, so redirections like >nul
# stay inside cmd and do NOT create a file called ‘nul’.
SHELL        := cmd
.SHELLFLAGS  := /C

APP_NAME     := Helios
EXE         ?= $(APP_NAME).exe          # OpenBench passes EXE=Helios‑<sha>[.exe]
PACKED       = build\dist\Helios.exe    # created by Gradle’s warpPack

# ---------- derive the real output file name -------------------------------
# If $(EXE) already ends with .exe -> use it directly.
# Otherwise we’ll build <name>.exe and ALSO copy a twin without the extension.
ifeq ($(suffix $(EXE)),.exe)
OUT_EXE   := $(EXE)
EXTRA_COPY :=
else
OUT_EXE   := $(EXE).exe
EXTRA_COPY := $(EXE)
endif

.PHONY: all clean

# ---------------------------------------------------------------------------
all: $(OUT_EXE)
# ---------------------------------------------------------------------------

# 1) build the single‑file executable
$(PACKED):
	@echo Building with Gradle …
	@".\\gradlew.bat" --no-daemon --console=plain warpPack

# 2) copy / rename exactly as OpenBench wants
$(OUT_EXE): $(PACKED)
	@echo Copying to $(OUT_EXE) …
	@if not exist "$(dir $(OUT_EXE))" mkdir "$(dir $(OUT_EXE))"
	@copy /Y "$(PACKED)" "$(OUT_EXE)" >nul
ifdef EXTRA_COPY
	@copy /Y "$(PACKED)" "$(EXTRA_COPY)" >nul
endif
	@echo Done.

# 3) housekeeping
clean:
	@echo Cleaning …
	-@".\\gradlew.bat" --no-daemon clean
	-@del /Q "$(PACKED)"       2>nul
	-@del /Q "$(OUT_EXE)"      2>nul
ifdef EXTRA_COPY
	-@del /Q "$(EXTRA_COPY)"   2>nul
endif
