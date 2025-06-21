# ───────── Helios Makefile for OpenBench (Linux-only) ─────────
OUT_DIR       ?= ./Helios          # where OpenBench will look
GRADLE_FLAGS  ?=

.DEFAULT_GOAL := all
.PHONY: all clean

all: $(OUT_DIR)/bin/helios

$(OUT_DIR)/bin/helios:
	@echo "==> Ensuring gradlew is executable"
	chmod +x ./gradlew

	@echo "==> Building distribution with Gradle installDist"
	./gradlew --no-daemon clean installDist $(GRADLE_FLAGS)

	@echo "==> Copying distribution to $(OUT_DIR)"
	rm -rf $(OUT_DIR)
	cp -r build/install/Helios $(OUT_DIR)

	@echo "==> Build complete – launcher at $(OUT_DIR)/bin/helios"

clean:
	./gradlew --no-daemon clean
	rm -rf build/install $(OUT_DIR)
# --------------------------------------------------------------
