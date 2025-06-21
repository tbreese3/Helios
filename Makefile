WRAPPER := ./gradlew
DISTDIR := build/install/Helios
PKGDIR  := Helios
LAUNCH  := $(PKGDIR)/bin/helios

.PHONY: all clean
all: $(LAUNCH)

$(DISTDIR)/bin/helios:
	chmod +x $(WRAPPER)
	$(WRAPPER) --no-daemon clean installDist

$(LAUNCH): $(DISTDIR)/bin/helios
	rm -rf $(PKGDIR)
	cp -r $(DISTDIR) $(PKGDIR)
	chmod +x $@

clean:
	rm -rf $(PKGDIR) build
