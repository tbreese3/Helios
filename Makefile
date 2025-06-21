# ───── Helios / Makefile – whitespace-free, OpenBench-safe ─────

WRAPPER := ./gradlew
EXE_DIR := Helios                   # final runtime dir copied back to worker
HASH    := $(shell test -d .git && git rev-parse --short HEAD || echo $$RANDOM)
OUT_BIN := Helios-$(HASH)           # worker expects this exact filename

.PHONY: all clean
all: $(OUT_BIN)

# 1) build with Gradle ------------------------------------------------------
build:
	@echo "==> gradle installDist"
	chmod +x $(WRAPPER)
	$(WRAPPER) --no-daemon clean installDist

# 2) package for OpenBench --------------------------------------------------
$(OUT_BIN): build
	@echo "==> packaging distribution"
	rm -rf $(EXE_DIR)
	install_dir=$$(ls -d build/install/Helios* 2>/dev/null | head -n 1) && \
	[ -n "$$install_dir" ] || { echo "ERROR: no build/install/Helios* dir"; exit 1; } ; \
	cp -r "$$install_dir" $(EXE_DIR) ; \
	launcher=$$(find $(EXE_DIR)/bin -type f -perm -u+x | head -n 1) && \
	[ -n "$$launcher" ] || { echo "ERROR: no executable in $${install_dir}/bin"; exit 1; } ; \
	cp "$$launcher" $(OUT_BIN) ; \
	chmod +x $(OUT_BIN)

clean:
	rm -rf $(EXE_DIR) $(OUT_BIN) build
