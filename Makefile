# ─────────────  Helios Makefile (Windows + Warp)  ─────────────
# Called by OpenBench with:  make EXE=<file‑name>
# The recipe:
#   1. gradlew warpPack      → build/dist/Helios.exe
#   2. copy that file to the requested name (both with & without .exe)

APP_NAME  := Helios
GRADLE    := ./gradlew           # works in MSYS / Git‑Bash & on Linux
EXE      ?= $(APP_NAME)          # OpenBench sets EXE=Helios-<sha>
PACKED    := build/dist/Helios.exe   # output of warpPack

# Helpers to decide if EXE already ends with .exe  (case‑insensitive)
lower     = $(shell echo "$(1)" | tr '[:upper:]' '[:lower:]')
HAS_EXE   = $(filter %.exe,$(call lower,$(EXE)))

.PHONY: all clean

# ---------- default target --------------------------------------------------
all: $(EXE)

# ---------- build the packed binary with Gradle -----------------------------
$(PACKED):
	@echo ">> building single‑file exe with Gradle"
	$(GRADLE) --no-daemon --console=plain warpPack

# ---------- copy/rename for OpenBench ---------------------------------------
$(EXE): $(PACKED)
	@echo ">> copying to $(EXE)"
	# Ensure build succeeded
	test -f "$(PACKED)"
	# 1) copy exactly the name OpenBench asked for
	cp -f "$(PACKED)" "$(EXE)"
	# 2) if EXE lacked .exe, also create the .exe twin
ifeq ($(HAS_EXE),)
	cp -f "$(PACKED)" "$(EXE).exe"
endif
	@echo ">> done"

# ---------- cleanup ---------------------------------------------------------
clean:
	@echo ">> cleaning"
	-$(GRADLE) --no-daemon --console=plain clean
	-rm -f "$(PACKED)" "$(EXE)" "$(EXE).exe"
