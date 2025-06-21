# ──────────────── Helios Makefile (Linux-only) ────────────────
#
#   make           → build app-image & copy to ./Helios
#   make clean     → wipe Gradle + jpackage artefacts
#
#   NOTE: OpenBench calls “make” without arguments and
#         launches the binary from the copy in $(OUT_PATH).

# -------- user-tweakable variables ---------------------------------
OUT_PATH ?= ./Helios           # where the ready-to-run folder is copied
GRADLE_OPTS ?=                 # any extra gradle args you might want
# -------------------------------------------------------------------

# Default goal
.PHONY: all
all: $(OUT_PATH)/bin/Helios

# Build target ------------------------------------------------------
$(OUT_PATH)/bin/Helios:
	@echo "==> Ensuring gradlew is executable"
	chmod +x ./gradlew

	@echo "==> Running Gradle jpackage (app-image)"
	./gradlew --no-daemon clean jpackage \
	          -PjpackageImageType=app-image \
	          $(GRADLE_OPTS)

	@echo "==> Copying app-image to $(OUT_PATH)"
	rm -rf  $(OUT_PATH)
	cp -r   build/jpackage/Helios $(OUT_PATH)

	@echo "==> DONE – binary is at $(OUT_PATH)/bin/Helios"

# Clean target ------------------------------------------------------
.PHONY: clean
clean:
	./gradlew --no-daemon clean
	rm -rf build/jpackage
	rm -rf $(OUT_PATH)
# -------------------------------------------------------------------
