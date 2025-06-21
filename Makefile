# ─── Helios OpenBench build ────────────────────────────────────────────────
EXE          := Helios                 # engine name as shown on the site
OUT_PATH     := ./Helios               # folder OpenBench will copy
GRADLEW      := ./gradlew
LAUNCHER     := $(OUT_PATH)/bin/helios # actual script Gradle produces (lower-case)

.PHONY: all build copy launcher clean

all: launcher

# 1 – ensure wrapper is executable, run Gradle installDist
build:
	@echo "==> Making Gradle wrapper executable"
	chmod +x $(GRADLEW)
	@echo "==> Building distribution with Gradle installDist"
	$(GRADLEW) --no-daemon clean installDist

# 2 – copy Gradle’s installDist output into $(OUT_PATH)
copy: build
	@echo "==> Copying distribution to $(OUT_PATH)"
	rm -rf $(OUT_PATH)
	cp -r build/install/$(EXE) $(OUT_PATH)

# 3 – export a single launcher executable for OpenBench
launcher: copy
	@echo "==> Exporting launcher for OpenBench"
	chmod +x $(LAUNCHER)
	# create a *stable* symlink that OpenBench will use as the binary name
	ln -sf $(LAUNCHER) $(OUT_PATH)/$(EXE)

clean:
	rm -rf build $(OUT_PATH)
