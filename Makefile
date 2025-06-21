# ─────────────── Helios Makefile ───────────────
# ``make        `` → builds wrapper + fat-jar
# ``make clean `` → deletes all generated files
# ------------------------------------------------
GRADLE      := ./gradlew               # wrapper supplied by the repo
APP_NAME    := Helios                  # module / main-class base name
EXE        ?= $(APP_NAME)              # override with “EXE=…” if OB asks
FAT_TASK    := fatJar                  # Gradle task we created
JAR_DIR     := build/libs
JAR         := $(shell ls -t $(JAR_DIR)/*-all.jar 2>/dev/null | head -n1)

# --------------- default target -----------------
.PHONY: all
all: $(EXE)                                  # wrapper depends on jar

# --------------- build fat-jar ------------------
$(JAR):
	@echo ">> building fat-jar with Gradle"
	bash $(GRADLE) --no-daemon --console=plain $(FAT_TASK)

# --------------- build wrapper ------------------
$(EXE): $(JAR)
	@echo ">> creating wrapper $(EXE)"
	printf '%s\n' '#!/usr/bin/env bash' \
	              'DIR=$$(dirname "$$0")' \
	              'exec java -jar "$$DIR/'"$(EXE)"'-all.jar" "$$@"' > $(EXE)
	chmod +x $(EXE)
	@echo ">> copying fat-jar next to wrapper"
	cp -fp $(JAR) $(EXE)-all.jar

# --------------- cleaning -----------------------
.PHONY: clean
clean:
	@echo ">> cleaning"
	-@bash $(GRADLE) --no-daemon --console=plain clean
	-@rm -rf build $(EXE) $(EXE)-all.jar
# ------------------------------------------------
