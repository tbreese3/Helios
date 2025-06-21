# ---------- Helios build for OpenBench -------------------
WRAPPER := ./gradlew
PKGDIR  := build/install/Helios      # created by Gradle installDist
OUTDIR  := Helios                    # final folder OB expects

.PHONY: all clean
all: $(OUTDIR)/bin/helios            # what OB will execute

# 1. Produce the Gradle distribution
$(PKGDIR)/bin:
	@chmod +x $(WRAPPER)
	$(WRAPPER) --no-daemon clean installDist

# 2. Copy it and expose a lowercase launcher
$(OUTDIR)/bin/helios: $(PKGDIR)/bin
	@echo "==> Copying distribution"
	rm -rf $(OUTDIR)
	cp -r $(PKGDIR) $(OUTDIR)

	@echo "==> Discovering launcher"
	launcher=$$(find $(OUTDIR)/bin -maxdepth 1 -type f -perm -u+x ! -name '*.bat' | head -n 1); \
	if [ -z "$$launcher" ]; then echo "ERROR: no executable found in $(OUTDIR)/bin"; exit 1; fi; \
	echo "   found $$launcher"; \
	chmod +x "$$launcher"; \
	ln -sf "$$(basename $$launcher)" $(OUTDIR)/bin/helios

clean:
	rm -rf $(OUTDIR) build
