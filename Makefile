# ───────── Helios Makefile (Windows‑only commands) ─────────
APP_NAME := Helios
GRADLE   := gradlew.bat

# OpenBench passes something like EXE=Helios-9B426B59 or Helios-9B426B59.exe
EXE     ?= $(APP_NAME).exe

# Output from Gradle’s warpPack
PACKED  := build\dist\Helios.exe

# Does $(EXE) already end with .exe (case‑insensitive)?
ifeq ($(filter %.exe,$(shell echo $(EXE) | tr A-Z a-z)),)
EXE_WITH_EXT := $(EXE).exe
else
EXE_WITH_EXT := $(EXE)
endif

.PHONY: all clean

all: $(EXE_WITH_EXT)

# -----------------------------------------------------------------
# 1) build the single‑file exe
$(PACKED):
	@echo Building with Gradle…
	cmd /c "$(GRADLE)" --no-daemon --console=plain warpPack

# -----------------------------------------------------------------
# 2) copy / rename exactly as OpenBench expects
$(EXE_WITH_EXT): $(PACKED)
	@echo Copying to $(EXE_WITH_EXT) …
	@REM create containing dir if caller asked for nested path
	cmd /c "if not exist \"$(dir $(EXE_WITH_EXT))\" mkdir \"$(dir $(EXE_WITH_EXT))\""
	cmd /c copy /Y "$(PACKED)" "$(EXE_WITH_EXT)" >nul
	@REM also drop the .exe if OpenBench asked for a name without extension
ifeq ($(EXE_WITH_EXT),$(EXE))
else
	cmd /c copy /Y "$(PACKED)" "$(EXE)" >nul
endif
	@echo Done.

# -----------------------------------------------------------------
clean:
	@echo Cleaning…
	- cmd /c "$(GRADLE)" --no-daemon clean
	- cmd /c del /Q "$(PACKED)"     2>nul
	- cmd /c del /Q "$(EXE_WITH_EXT)" "$(EXE)" 2>nul
