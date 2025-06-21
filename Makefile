# ─────────────── Helios Makefile ───────────────
# Purpose : build Gradle project & leave runnable binary at project root
# Targets :
#   make / make build   – build the engine
#   make clean          – remove all build artefacts
# ------------------------------------------------

GRADLE      := ./gradlew            # Gradle wrapper
APP_NAME    := Helios
DIST_DIR    := build/install/$(APP_NAME)
BIN_SRC     := $(DIST_DIR)/bin/$(APP_NAME)   # produced by Gradle
BIN_OUT     := $(APP_NAME)                   # copy here for OpenBench

# ── default target ──────────────────────────────
.PHONY: all
all: $(BIN_OUT)

# Build & place binary where OpenBench expects it
$(BIN_SRC):
	@echo ">> Building $(APP_NAME) with Gradle"
	bash $(GRADLE) --no-daemon --console=plain installDist
	@chmod +x $@

$(BIN_OUT): $(BIN_SRC)
	@echo ">> Copying launcher to repository root"
	cp $< $@
	@chmod +x $@

# Handy alias
.PHONY: build
build: all

# Clean everything Gradle produced
.PHONY: clean
clean:
	@echo ">> Cleaning Gradle artefacts"
	bash $(GRADLE) --no-daemon --console=plain clean
	rm -rf build $(BIN_OUT)
# ────────────────────────────────────────────────
