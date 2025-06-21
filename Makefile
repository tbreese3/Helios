# ─── Helios OpenBench build ────────────────────────────────────────────────
EXE       := Helios          # name shown on OpenBench site
OUT_PATH  := ./Helios        # final folder OpenBench copies
GRADLEW   := ./gradlew       # Gradle wrapper

.PHONY: all build copy launcher clean
all: launcher

# 1) build the distribution with installDist -------------------------------
build:
	@echo "==> Gradle installDist"
	chmod +x $(GRADLEW)
	$(GRADLEW) --no-daemon clean installDist

# 2) copy whichever Helios-* directory Gradle emitted ----------------------
copy: build
	@echo "==> Copying distribution to $(OUT_PATH)"
	rm -rf $(OUT_PATH)
	install_dir=$$(ls -d build/install/Helios* | head -n 1);                \
	[ -z "$$install_dir" ] && { echo "No install/Helios* dir found"; exit 1; }; \
	cp -r "$$install_dir" $(OUT_PATH)

# 3) export a single launcher script for OpenBench -------------------------
launcher: copy
	@echo "==> Exporting launcher"
	launcher=$$(ls $(OUT_PATH)/bin/* | head -n 1);                           \
	chmod +x "$$launcher";                                                   \
	ln -sf "$$launcher" $(OUT_PATH)/$(EXE)

# 4) optional cleanup ------------------------------------------------------
clean:
	rm -rf build $(OUT_PATH)
