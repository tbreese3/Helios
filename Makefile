# ---------- Helios Makefile for OpenBench (Linux only) ----------
EXE := Helios                # name OpenBench will copy
WRAPPER := $(EXE)            # convenience

.PHONY: all clean
all: $(WRAPPER)

$(WRAPPER):
	@echo "==> Gradle installDist"
	./gradlew --no-daemon clean installDist

	@echo "==> Creating wrapper script"
	{ \
	  echo '#!/usr/bin/env bash'; \
	  echo 'DIR="$(cd "$(dirname "$$0")" && pwd)"'; \
	  echo '"$$DIR"/build/install/Helios/bin/Helios "$$@"'; \
	} > $(WRAPPER)
	chmod +x $(WRAPPER)

clean:
	rm -rf build/install Helios $(WRAPPER)
# ----------------------------------------------------------------
