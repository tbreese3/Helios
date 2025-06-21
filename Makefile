# ────────────────────────────  Helios / Makefile  ────────────────────────────
EXE       ?= Helios
OUT_PATH  ?= ./Helios
GRADLE    := ./gradlew          # wrapper file in repo root

.DEFAULT_GOAL := $(EXE)

# ── 1 – compile / assemble with Gradle --------------------------------------
build:
	@echo "==> Making Gradle wrapper executable"
	@chmod +x $(GRADLE) 2>/dev/null || true
	@echo "==> Building distribution with Gradle installDist"
	@$(GRADLE) --no-daemon clean installDist

# ── 2 – copy full distribution for OpenBench --------------------------------
copy: build
	@echo "==> Copying distribution to $(OUT_PATH)"
	@rm -rf  $(OUT_PATH)
	@mkdir -p $(OUT_PATH)
	@cp -r   build/install/Helios/* $(OUT_PATH)

# ── 3 – export single launcher ----------------------------------------------
LAUNCHER := $(OUT_PATH)/bin/helios     # ← lower-case, no extra spaces

launcher: copy
	@echo "==> Exporting launcher for OpenBench"
	@ln -sf "$(LAUNCHER)"              "$(OUT_PATH)/$(EXE)"
	@chmod +x  "$(LAUNCHER)"           "$(OUT_PATH)/$(EXE)"

# ── 4 – final target ---------------------------------------------------------
$(EXE): launcher
	@echo "==> DONE – binary is at $(OUT_PATH)/$(EXE)"

# ── housekeeping ------------------------------------------------------------
.PHONY: clean
clean:
	@rm -rf build $(OUT_PATH)
# ─────────────────────────────────────────────────────────────────────────────
