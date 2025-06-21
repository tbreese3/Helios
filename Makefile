# ───────────── Helios Makefile ─────────────
GRADLE    := ./gradlew
APP_NAME  := Helios
EXE      ?= $(APP_NAME)            # OpenBench passes EXE=Helios-<sha>
FAT_TASK  := fatJar                # Gradle task that makes the fat-jar
JAR_GLOB  := build/libs/*-all.jar  # shell pattern – don’t pre-expand!

.PHONY: all clean
# default target --------------------------------------------------------------
all: $(EXE)

# build wrapper (depends on jar) ---------------------------------------------
$(EXE): $(JAR_GLOB)
	@echo ">> creating wrapper $@"
	printf '%s\n' '#!/usr/bin/env bash' \
	              'DIR=$$(dirname "$$0")' \
	              'exec java -jar "$$DIR/'"$@-all.jar"'" "$$@"' > $@
	chmod +x $@
	@echo ">> copying fat-jar next to wrapper"
	cp -fp $(JAR_GLOB) $@-all.jar   # shell expands the glob *now*

# build fat-jar if *no* jar exists yet ---------------------------------------
$(JAR_GLOB):
	@echo ">> building fat-jar with Gradle"
	bash $(GRADLE) --no-daemon --console=plain $(FAT_TASK)

# clean ----------------------------------------------------------------------
clean:
	@echo ">> cleaning"
	-@bash $(GRADLE) --no-daemon --console=plain clean
	-@rm -rf build $(EXE) $(EXE)-all.jar
# ----------------------------------------------------------------------------
