# ───── Helios Makefile (patched) ─────
GRADLE    := ./gradlew
APP_NAME  := Helios
EXE      ?= $(APP_NAME)
LAUNCHER  := build/install/$(APP_NAME)/bin/$(APP_NAME)   # ← no wrap!

.PHONY: all build clean
all build: $(EXE)

$(EXE):
	@echo ">> building with Gradle"
	bash $(GRADLE) --no-daemon --console=plain installDist

	@echo ">> copying launcher to project root as $(EXE)"
	cp -f "$(LAUNCHER)" "$(EXE)"
	chmod +x "$(EXE)"

clean:
	bash $(GRADLE) --no-daemon --console=plain clean
	rm -rf build "$(EXE)"
