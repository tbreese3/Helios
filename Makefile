# ───────── Helios Makefile ─────────
# Lets OpenBench (or you) run `make EXE=<outfile>` to obtain a
# ready-to-run launcher in the *project root*.
# Default EXE is “Helios” so local workflows stay unchanged.
# -----------------------------------

GRADLE   := ./gradlew           # Gradle wrapper
APP_NAME := Helios              # Name inside build/install/…
EXE      ?= $(APP_NAME)         # “make EXE=Helios-abcdef12” when OpenBench calls us

APP_DIR  := build/install/$(APP_NAME)
BIN_SRC  := $(APP_DIR)/bin/$(APP_NAME)   # Gradle’s output
BIN_DST  := $(EXE)                       # what OpenBench expects after make

# ---------- targets ----------------------------------------------------
.PHONY: all build clean
all   : $(BIN_DST)
build : all

# Build with Gradle, then copy/rename the launcher
$(BIN_DST): $(BIN_SRC)
	@echo ">> building with Gradle"
	bash $(GRADLE) --no-daemon --console=plain installDist

	@echo ">> copying launcher to project root as $(BIN_DST)"
	cp -f $(BIN_SRC) $(BIN_DST)
	chmod +x $(BIN_DST)

# Gradle clean + our own artefacts
clean :
	$(GRADLE) --no-daemon --console=plain clean
	rm -rf build
	rm -f  $(EXE)
# -----------------------------------------------------------------------
