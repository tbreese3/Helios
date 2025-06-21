# ─── Helios / Makefile ────────────────────────────────────────────────
WRAPPER := ./gradlew
DISTDIR := build/install/Helios      # Gradle output folder (no hash)
PKGDIR  := Helios                    # Folder sent back to OpenBench
LAUNCH  := $(PKGDIR)/bin/helios      # Final executable path

.PHONY: all clean
all: $(LAUNCH)

# 1) Build distribution with Gradle (installDist)
$(DISTDIR)/bin/helios:
	chmod +x $(WRAPPER)
	$(WRAPPER) --no-daemon clean installDist

# 2) Copy distribution into ./Helios
$(LAUNCH): $(DISTDIR)/bin/helios
	rm -rf $(PKGDIR)
	cp -r $(DISTDIR) $(PKGDIR)
	chmod +x $@

clean:
	rm -rf $(PKGDIR) build
# ──────────────────────────────────────────────────────────────────────
