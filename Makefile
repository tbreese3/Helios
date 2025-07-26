# ───────── Helios Makefile (pure‑cmd) ─────────
APP_NAME := Helios
GRADLE   := gradlew.bat

# OpenBench invokes:  make EXE=Helios‑<sha>[.exe]
EXE      ?= $(APP_NAME).exe          # default when you run locally

# Result produced by `gradlew warpPack`
PACKED   := build\dist\Helios.exe

# Does $(EXE) already end with .exe (case‑sensitive is fine for Windows)?
ifeq ($(suffix $(EXE)),.exe)
COPY_EXTRA :=
else
COPY_EXTRA := copy /Y "$(PACKED)" "$(EXE).exe" >nul
endif

.PHONY: all clean

# ---------- default target --------------------------------------------------
all: $(EXE)

# ---------- build the single‑file exe via Gradle ----------------------------
$(PACKED):
	@echo Building with Gradle…
	cmd /c "$(GRADLE)" --no-daemon --console=plain warpPack

# ---------- copy / rename for OpenBench -------------------------------------
$(EXE): $(PACKED)
	@echo Copying to $(EXE) …
	@REM create parent dir if EXE has a path component
	cmd /c "if not exist \"$(dir $(EXE))\" mkdir \"$(dir $(EXE))\""
	cmd /c copy /Y "$(PACKED)" "$(EXE)" >nul
	$(COPY_EXTRA)
	@echo Done.

# ---------- cleanup ---------------------------------------------------------
clean:
	@echo Cleaning…
	- cmd /c "$(GRADLE)" --no-daemon clean
	- cmd /c del /Q "$(PACKED)"       2>nul
	- cmd /c del /Q "$(EXE)"          2>nul
	- cmd /c del /Q "$(EXE).exe"      2>nul
