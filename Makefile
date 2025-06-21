# ───────── Helios Makefile ─────────
GRADLE    := ./gradlew            # Gradle wrapper
APP_NAME  := Helios               # Gradle’s “application” name
EXE      ?= $(APP_NAME)           # OpenBench passes EXE=<sha>, default is Helios
LAUNCHER  := build/install/$(APP_NAME)/bin/$(APP_NAME)

.PHONY: all build clean
all build: $(EXE)

# -----------------------------------------------------------------
# Single target: build with Gradle, put the launcher where requested
$(EXE):
	@echo ">> building with Gradle"
	bash $(GRADLE) --no-daemon --console=plain installDist

	@echo ">> copying launcher to project root as $(EXE)"
	cp -f $(LAUNCHER) $(EXE)
	chmod +x $(EXE)
# -----------------------------------------------------------------

clean:
	bash $(GRADLE) --no-daemon --console=plain clean
	rm -rf build
	rm -f $(EXE)
