# ─────────  Helios Makefile (bare‑bones Windows)  ─────────
APP_NAME := Helios

EXE     ?= $(APP_NAME).exe                    # OpenBench sets EXE=Helios‑<sha>[.exe]
PACKED   = build\dist\Helios.exe              # produced by warpPack
GRADLE   = gradlew.bat

.PHONY: all clean           # default + cleaning targets
# ---------------------------------------------------------------------------

all: $(EXE)                 # What OpenBench calls
# ---------------------------------------------------------------------------

# Build the single‑file exe
$(PACKED):
	cmd /c "\"$(GRADLE)\" --no-daemon --console=plain warpPack"

# Copy / rename for OpenBench
$(EXE): $(PACKED)
	cmd /c "if not exist \"$(dir $(EXE))\" mkdir \"$(dir $(EXE))\""
	cmd /c "copy /Y \"$(PACKED)\" \"$(EXE)\" >nul"
	@rem Also write the .exe twin in case EXE had no extension
	cmd /c "copy /Y \"$(PACKED)\" \"$(EXE).exe\" >nul 2>nul"

# House‑keeping
clean:
	- cmd /c "\"$(GRADLE)\" --no-daemon clean"
	- cmd /c "del /Q \"$(PACKED)\"       2>nul"
	- cmd /c "del /Q \"$(EXE)\"          2>nul"
	- cmd /c "del /Q \"$(EXE).exe\"      2>nul"
