# ─── Helios / Makefile ──────────────────────────────────────────────────
WRAPPER := ./gradlew                     # Gradle wrapper
DST_DIR := build/install/Helios          # Gradle’s output dir
PKG_DIR := Helios                        # folder sent back to worker
LAUNCH  := helios                        # script Gradle generates

.PHONY: all clean
all: $(PKG_DIR)/bin/$(LAUNCH)

# 1) Build distribution with Gradle
$(DST_DIR)/bin/$(LAUNCH):
	chmod +x $(WRAPPER)
	$(WRAPPER) --no-daemon clean installDist

# 2) Copy distribution into a clean local folder
$(PKG_DIR)/bin/$(LAUNCH): $(DST_DIR)/bin/$(LAUNCH)
	rm -rf $(PKG_DIR)
	cp -r $(DST_DIR) $(PKG_DIR)
	chmod +x $@

clean:
	rm -rf $(PKG_DIR) build
# ────────────────────────────────────────────────────────────────────────
