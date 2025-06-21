# ---------- Helios Makefile (root) ------------------------------------------
# Targets:
#   make            -> builds a self-contained native image with Gradle
#   make clean      -> removes Gradle build output
#
# Variables OpenBench cares about
EXE      ?= Helios          # final binary dir name
OUT_PATH ?= .               # where the binary should end up

# Gradle wrapper & where jpackage puts the files
GRADLEW       := ./gradlew
JPACKAGE_DIR  := build/jpackage/$(EXE)/$(EXE)   # Gradle default

.PHONY: all release clean

# “all” is the default target that OpenBench will call
all: release

release:
	@echo "==> Running Gradle jpackage"
        chmod +x ./gradlew
	$(GRADLEW) --no-daemon clean jpackage
	@echo "==> Copying native image to $(OUT_PATH)"
	cp -r $(JPACKAGE_DIR)/* $(OUT_PATH)

clean:
	$(GRADLEW) clean
	rm -rf build/jpackage
# ---------------------------------------------------------------------------
