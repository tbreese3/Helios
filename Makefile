# ─────────────  Helios Makefile  ─────────────
GRADLE    := ./gradlew
APP_NAME  := Helios
EXE      ?= $(APP_NAME)            # OpenBench passes EXE=Helios-<sha>
JAR_GLOB  := build/libs/*-all.jar  # any fat-jar Gradle produces
TMP_JAR   := /tmp/helios-$$.jar    # scratch when the wrapper extracts itself

.PHONY: all clean
all: $(EXE)                        # default target

# ---------------------------------------------------------------------------
# Build the fat-jar if it does not exist yet
# ---------------------------------------------------------------------------
$(JAR_GLOB):
	@echo ">> building fat-jar with Gradle"
	bash $(GRADLE) --no-daemon --console=plain fatJar

# ---------------------------------------------------------------------------
# Build a self-extracting wrapper  (single file ⇒ OpenBench friendly)
# ---------------------------------------------------------------------------
$(EXE): $(JAR_GLOB)
	@echo ">> creating self-contained executable  $@"
	{ \
		echo '#!/usr/bin/env bash'; \
		echo 'set -e'; \
		echo 'SCRIPT=$$(readlink -f "$$0")'; \
		echo 'PAYLOAD_LINE=$$(grep -a -n "^__JAR_PAYLOAD_BELOW__$$" "$$SCRIPT" 2>/dev/null | cut -d: -f1)'; \
		echo 'PAYLOAD_START=$$((PAYLOAD_LINE+1))'; \
		echo 'tail -n +$$PAYLOAD_START "$$SCRIPT" > $(TMP_JAR)'; \
		echo 'exec java -jar $(TMP_JAR) "$$@"'; \
		echo 'exit 0'; \
		echo '__JAR_PAYLOAD_BELOW__'; \
		cat $(JAR_GLOB); \
	} > $@
	chmod +x $@

# ---------------------------------------------------------------------------
# House-keeping
# ---------------------------------------------------------------------------
clean:
	@echo ">> cleaning"
	-@bash $(GRADLE) --no-daemon --console=plain clean
	-@rm -rf build $(EXE)
# ---------------------------------------------------------------------------
