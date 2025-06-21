# ─────────── Helios Makefile (one-page, tested) ───────────
GRADLE := ./gradlew
APP    := Helios
EXE   ?= Helios-ob                    # what OpenBench passes
JAR    := build/libs/Helios-all.jar   # gradle fatJar output

.PHONY: all clean
all: $(EXE)

# ── build the fat-jar ─────────────────────────────────────
$(JAR):
	@echo ">> building fat-jar"
	bash $(GRADLE) --no-daemon --console=plain fatJar

# ── build wrapper ─────────────────────────────────────────
$(EXE): $(JAR)
	@echo ">> creating wrapper $@"
	printf '%s\n' '#!/usr/bin/env bash' \
	              'DIR=$$(dirname "$$0")' \
	              'exec java -jar "$$DIR/'"$(EXE)"'-all.jar" "$$@"' > $@
	chmod +x $@
	@echo ">> copying fat-jar next to wrapper"
	cp -fp $(JAR) $(EXE)-all.jar

# ── housekeeping ─────────────────────────────────────────
clean:
	@echo ">> cleaning"
	- bash $(GRADLE) --no-daemon --console=plain clean
	- rm -rf build $(EXE) $(EXE)-all.jar
# ─────────────────────────────────────────────────────────
