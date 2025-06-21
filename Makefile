# ───────── Makefile for Helios on OpenBench (Linux) ──────────
# OpenBench passes EXE and OUT_PATH; fall back for local runs.
EXE       ?= Helios
OUT_PATH  ?= ./out                 # when testing locally

# main target that OpenBench calls: it must drop a launcher named $(EXE)
# directly inside $(OUT_PATH)
.PHONY: all
all: $(OUT_PATH)/$(EXE)

#
# ----- build pipeline ----------------------------------------------------
#
# 1) make sure the Gradle wrapper is executable
# 2) build a self-contained “installDist” image
# 3) copy that whole image (+ a convenience launcher) into $(OUT_PATH)
#
$(OUT_PATH)/$(EXE):
	@echo "==> Ensuring gradlew is executable"
	chmod +x ./gradlew

	@echo "==> Building distribution with Gradle installDist"
	./gradlew --no-daemon clean installDist

	@echo "==> Copying full distribution to $(OUT_PATH)"
	rm -rf  $(OUT_PATH)
	mkdir -p $(OUT_PATH)
	cp -r build/install/Helios/* $(OUT_PATH)

	@echo "==> Exporting single launcher for OpenBench"
	# Gradle’s Unix wrapper is lower-case ‘helios’
	ln -sf $(OUT_PATH)/bin/helios $(OUT_PATH)/$(EXE)
	chmod +x $(OUT_PATH)/$(EXE)

	@echo "==> Build complete – launcher at $(OUT_PATH)/$(EXE)"

#
# ----- helpers -----------------------------------------------------------
#
.PHONY: clean
clean:
	rm -rf build $(OUT_PATH)
# -------------------------------------------------------------------------
