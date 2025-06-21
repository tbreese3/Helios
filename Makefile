# ───────── Helios Makefile for OpenBench (Linux-only) ─────────
OUT_DIR       ?= ./Helios          # where CuteChess will look
GRADLE_FLAGS  ?=

.DEFAULT_GOAL := all
.PHONY: all clean

all: $(OUT_DIR) ./Helios          # <-- depend on the flat binary, too

$(OUT_DIR) ./Helios:              # build rule
	chmod +x ./gradlew

	@echo "==> Building distribution with Gradle installDist"
	./gradlew --no-daemon clean installDist $(GRADLE_FLAGS)

	@echo "==> Copying distribution to $(OUT_DIR)"
	rm -rf $(OUT_DIR)
	cp -r build/install/Helios $(OUT_DIR)

	@echo "==> Exporting single launcher for OpenBench"
	cp build/install/Helios/bin/helios ./Helios
	chmod +x ./Helios

	@echo "==> Build complete – launcher at ./Helios"

clean:
	./gradlew --no-daemon clean
	rm -rf build/install $(OUT_DIR) ./Helios
# --------------------------------------------------------------
