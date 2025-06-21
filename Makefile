# ----------  Helios build for OpenBench   -----------------
WRAPPER   := ./gradlew
BUILDDIR  := build/install
PKGNAME   := Helios            # Gradle installs here
OUTDIR    := Helios            # Copied here for OpenBench

.PHONY: all clean
all: $(OUTDIR)/bin/helios      # final target that OB will call

# 1. Run Gradle (once)
$(BUILDDIR)/$(PKGNAME)/bin:
	@chmod +x $(WRAPPER)
	$(WRAPPER) --no-daemon clean installDist

# 2. Copy the distribution & expose a lowercase launcher
$(OUTDIR)/bin/helios: $(BUILDDIR)/$(PKGNAME)/bin
	@echo "==> Copying distribution"
	rm -rf $(OUTDIR)
	cp -r $(BUILDDIR)/$(PKGNAME) $(OUTDIR)
	@echo "==> Making launcher executable"
	# find first executable in bin that is **not** a .bat file
	launcher=$$(find $(OUTDIR)/bin -maxdepth 1 -type f -perm -u+x ! -name '*.bat' | head -n 1); \
	echo "   found $$launcher"; \
	chmod +x "$$launcher"; \
	ln -sf "$$(basename $$launcher)" $(OUTDIR)/bin/helios
	@echo "==> Ready  -> $(OUTDIR)/bin/helios"

clean:
	rm -rf $(OUTDIR) build
