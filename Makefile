# ───────── Helios Makefile (universal‑Windows) ─────────
APP_NAME := Helios

# absolute Windows path to gradlew.bat  (converts /c/... → C:\...)
GRADLE   := $(shell cygpath -w "$(CURDIR)/gradlew.bat" 2>nul || echo "$(CURDIR)\\gradlew.bat")

# OpenBench passes EXE=Helios‑<sha>  (maybe without .exe)
EXE     ?= $(APP_NAME).exe
PACKED   = build\dist\Helios.exe          # output of warpPack

# does $(EXE) already have .exe (case‑insensitive)?
ifeq ($(filter %.exe, $(shell echo $(EXE) | tr A-Z a-z)),)
EXE_WITH_EXT := $(EXE).exe
COPY_TWIN    := copy /Y "$(PACKED)" "$(EXE).exe" >nul
else
EXE_WITH_EXT := $(EXE)
COPY_TWIN    :=
endif

.PHONY: all clean

# --------------------------------------------------------------------------
all: $(EXE_WITH_EXT)
# --------------------------------------------------------------------------

# build the single‑file exe
$(PACKED):
	@echo ">> Building with Gradle…"
	cmd /c "\"$(
