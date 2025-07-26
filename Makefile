# ─────────────  Helios Makefile (Windows / Warp)  ─────────────
APP_NAME := Helios

# OpenBench passes EXE=Helios‑<sha>[.exe]; default for local runs:
EXE      ?= $(APP_NAME).exe

# Absolute Windows path to gradlew.bat  (pwd -W works in Git‑Bash)
WIN_CURDIR := $(shell pwd -W 2>/dev/null || echo $(CURDIR))
GRADLE     := $(WIN_CURDIR)\\gradlew.bat

# File produced by `gradlew warpPack`
PACKED := build\dist\Helios.exe

# Does $(EXE) already end with .exe (case‑insensitive)?
ifeq ($(filter %.exe,$(EXE)),)
TARGET_WITH_EXT := $(EXE).exe   # we’ll create this
COPY_TWIN       := yes          # also copy to the name w/o extension
else
TARGET_WITH_EXT := $(EXE)
COPY_TWIN       := no
endif

.PHONY: all clean

# ------------------------------------------------------------------
all: $(TARGET_WITH_EXT)
# ------------------------------------------------------------------

# 1) Build the single‑file executable
$(PACKED):
	@echo ">> Building with Gradle…"
	@cmd /c "\"$(GRADLE)\" --no-daemon --console=plain warpPack"

# 2) Copy / rename exactly as OpenBench wants
$(TARGET_WITH_EXT): $(PACKED)
	@echo ">> Copying to $(TARGET_WITH_EXT)…"
	@cmd /c "if not exist \"$(dir $(TARGET_WITH_EXT))\" mkdir \"$(dir $(TARGET_WITH_EXT))\""
	@cmd /c "copy /Y \"$(PACKED)\" \"$(TARGET_WITH_EXT)\" >nul"
ifeq ($(COPY_TWIN),yes)
	@cmd /c "copy /Y \"$(PACKED)\" \"$(EXE)\" >nul"
endif
	@echo ">> Done."

# 3) House‑keeping
clean:
	@echo ">> Cleaning…"
	-@cmd /c "\"$(GRADLE)\" --no-daemon clean"
	-@cmd /c "del /Q \"$(PACKED)\" 2>nul"
	-@cmd /c "del /Q \"$(TARGET_WITH_EXT)\" 2>nul"
ifeq ($(COPY_TWIN),yes)
	-@cmd /c "del /Q \"$(EXE)\" 2>nul"
endif

