# ────────────────────────────  Helios / Makefile  ────────────────────────────
# Builds a Gradle “installDist” distribution and exposes a single launcher that
# OpenBench can run.  Only Linux is supported here.

# ── Configuration -----------------------------------------------------------
EXE       ?= Helios                   # Logical engine name
OUT_PATH  ?= ./Helios                 # Where we stage the runnable package
GRADLE    := ./gradlew                # Wrapper shipped in the repo

# Make this the default target
.DEFAULT_GOAL := $(EXE)

# ── Build pipeline ----------------------------------------------------------

# 1. Ensure Gradle wrapper is executable
$(GRADLE):
	@chmod +x $(GRADLE)

# 2. Compile & assemble the “installDist” distribution
build: $(GRADLE)
	@echo "==> Building distribution with Gradle installDist"
	@$(GRADLE) --no-daemon clean installDist

# 3. Copy the full distro to $(OUT_PATH)
copy: build
	@echo "==> Copying full distribution to $(OUT_PATH)"
	@rm -rf  $(OUT_PATH)
	@mkdir -p $(OUT_PATH)
	@cp -r   build/install/Helios/* $(OUT_PATH)

# 4. Export a **single** launcher that OpenBench will execute
launcher: copy
	@echo "==> Exporting single launcher for OpenBench"
	@ln -sf "$(OUT_PATH)/bin/helios" "$(OUT_PATH)/$(EXE)"
	@chmod +x "$(OUT_PATH)/bin/helios" "$(OUT_PATH)/$(EXE)"

# 5. Final target (what OpenBench cares about)
$(EXE): launcher
	@echo "==> DONE – binary is at $(OUT_PATH)/$(EXE)"

# House-keeping
.PHONY: clean
clean:
	@rm -rf build $(OUT_PATH)
# ─────────────────────────────────────────────────────────────────────────────
