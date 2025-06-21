# scripts/Makefile  – wrapper for Gradle → jpackage
# OpenBench runs:
#   make -C scripts BIN=/tmp/.../Helios-main CPU=<flags> ...
# so we just honour the BIN variable.

all:
	./gradlew --no-daemon jpackage

	# jpackage puts the full bundle here ↓  (adjust if yours differs)
	cp -R build/jpackage/Helios/Helios/*  "$$BIN"

# Declare everything as phony so make never tries timestamp-logic
.PHONY: all