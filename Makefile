# ────────── Helios / Makefile (Linux-only, OpenBench-safe) ──────────
EXE_DIR := Helios                # final runtime directory
WRAPPER := ./gradlew             # Gradle wrapper
HASH    := $(shell git rev-parse --short HEAD)
OUT_BIN := Helios-$(HASH)        # file name OpenBench expects

.PHONY: all build package clean
all: $(OUT_BIN)

# 1) compile with Gradle ----------------------------------------------------
build:
	@echo "==> gradle installDist"
	chmod +x $(WRAPPER)
	$(WRAPPER) --no-daemon clean installDist

# 2) copy install dir & export launcher ------------------------------------
$(OUT_BIN): build
	@echo "==> copying distribution"
	rm -rf $(EXE_DIR)
	install_dir=$$(ls -d build/install/Helios* 2>/dev/null | head -n 1); \
	[ -z "$$install_dir" ] && { echo "ERROR: no build/install/Helios* dir"; exit 1; }; \
	cp -r "$$install_dir" $(EXE_DIR); \
	launcher=$$(find $(EXE_DIR)/bin -type f -perm -u+x | head -n 1); \
	[ -z "$$launcher" ] && { echo "ERROR: no executable in $${install_dir}/bin"; exit 1; }; \
	cp "$$launcher" $(OUT_BIN); \
	chmod +x $(OUT_BIN)

clean:
	rm -rf $(EXE_DIR) $(OUT_BIN) build
