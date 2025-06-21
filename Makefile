# ───── Helios / Makefile ─────
WRAPPER      := ./gradlew
BUILD_DIR    := build/install
PKG_DIR      := Helios           # directory copied back to the worker
HASH_CMD     := git rev-parse --short HEAD 2>/dev/null || date +%s

.PHONY: all clean
all: $(PKG_DIR).bin

# 1) build with Gradle ------------------------------------------------------
gradle-build:
	@echo "==> Gradle installDist"
	chmod +x $(WRAPPER)
	$(WRAPPER) --no-daemon clean installDist

# 2) package for OpenBench --------------------------------------------------
$(PKG_DIR).bin: gradle-build
	@set -e; \
	echo "==> packaging"; \
	rm -rf "$(PKG_DIR)"; \
	install_dir=$$(ls -d $(BUILD_DIR)/Helios* 2>/dev/null | head -n1); \
	[ -n "$$install_dir" ] || { echo "ERROR: no $(BUILD_DIR)/Helios* dir"; exit 1; }; \
	cp -r "$$install_dir" "$(PKG_DIR)"; \
	launcher=$$(find "$(PKG_DIR)/bin" -type f -perm -u+x | head -n1); \
	[ -n "$$launcher" ] || { echo "ERROR: no launcher in $$install_dir/bin"; exit 1; }; \
	hash=$$( $(HASH_CMD) ); \
	cp "$$launcher" "Helios-$$hash"; \
	chmod +x "Helios-$$hash"; \
	echo "==> produced Helios-$$hash"

# tidy
clean:
	rm -rf $(PKG_DIR) Helios-*.bin build
