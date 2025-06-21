# ─── Helios OpenBench Makefile (Linux) ─────────────────────────────────────
EXE        := Helios          # logical engine name
WRAPPER    := ./gradlew       # Gradle wrapper script
DIST_DIR   := Helios          # folder OpenBench expects
BUILD_DIR  := build/install   # where Gradle puts the distro

.PHONY: all build copy launcher clean
all: launcher
# ---------------------------------------------------------------------------

# 1. Build with Gradle ------------------------------------------------------
build:
	@echo "==> Gradle installDist"
	chmod +x $(WRAPPER)
	$(WRAPPER) --no-daemon clean installDist

# 2. Copy whichever Helios-* directory Gradle produced ---------------------
copy: build
	@echo "==> Copying distribution to $(DIST_DIR)"
	rm -rf $(DIST_DIR)
	install_dir=$$(ls -d $(BUILD_DIR)/Helios* 2>/dev/null | head -n 1); \
	[ -z "$$install_dir" ] && { echo "No Helios* install dir found"; exit 1; }; \
	cp -r "$$install_dir" $(DIST_DIR)

# 3. Export a *single* launcher for OpenBench ------------------------------
launcher: copy
	@echo "==> Exporting launcher"
	launcher=$$(ls $(DIST_DIR)/bin/* 2>/dev/null | head -n 1); \
	[ -z "$$launcher" ] && { echo "No launcher script found"; exit 1; }; \
	chmod +x "$$launcher"; \
	ln -sf "$$launcher" $(DIST_DIR)/$(EXE)

# 4. Optional cleanup ------------------------------------------------------
clean:
	rm -rf $(DIST_DIR) build
