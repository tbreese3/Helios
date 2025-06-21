# ---------- Helios Makefile for OpenBench (Linux only) ----------
EXE := Helios          # single file OpenBench will copy

.PHONY: all clean
all: $(EXE)

$(EXE):
	@chmod +x gradlew                # ←–– **ensure wrapper is runnable**
	@echo "==> Gradle installDist"
	./gradlew --no-daemon clean installDist

	@echo "==> Creating root wrapper"
	{ \
	  echo '#!/usr/bin/env bash'; \
	  echo 'DIR="$(cd "$(dirname "$$0")" && pwd)"'; \
	  echo '"$$DIR"/build/install/Helios/bin/Helios "$$@"'; \
	} > $(EXE)
	chmod +x $(EXE)

clean:
	rm -rf build/install Helios $(EXE)
# ----------------------------------------------------------------
