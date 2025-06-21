# ── Helios Makefile: zero-space, zero-symlink version ─────────────────────
EXE_DIR   := Helios                # where OpenBench will look
WRAPPER   := ./gradlew
BUILD_DIR := build/install

.PHONY: all build package clean

all: package

## 1) Gradle build ----------------------------------------------------------
build:
	@echo "==> gradle installDist"
	chmod +x $(WRAPPER)
	$(WRAPPER) --no-daemon clean installDist

## 2) Copy distro & make launcher executable --------------------------------
package: build
	@echo "==> copying distribution"
	rm -rf $(EXE_DIR)
	install_dir=$$(ls -d $(BUILD_DIR)/Helios* | head -n 1); \
	[ -z "$$install_dir" ] && { echo "ERROR: no Helios install dir"; exit 1; }; \
	cp -r "$$install_dir" $(EXE_DIR); \
	launcher=$$(ls $(EXE_DIR)/bin/* | head -n 1); \
	chmod +x "$$launcher"; \
	mv "$$launcher" $(EXE_DIR)/bin/helios

## optional tidy-up ---------------------------------------------------------
clean:
	rm -rf $(EXE_DIR) build
