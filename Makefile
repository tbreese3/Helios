# ─────────────  Helios Makefile (Windows + Warp)  ─────────────
APP_NAME := Helios
GRADLE   := ./gradlew
EXE     ?= $(APP_NAME)              # OpenBench passes EXE=Helios-<sha>

# Path to the file Gradle’s warpPack task creates – strip removes trailing blanks
PACKED   := $(strip build/dist/Helios.exe)

# Does EXE already end with .exe?  (case‑insensitive)
ifeq ($(filter %.exe,$(shell echo $(EXE) | tr A-Z a-z)),)
NEEDS_EXE_EXT := yes
endif

.PHONY: all clean warp

# ------------------------------------------------------------------
all: $(EXE)                         # default target OpenBench calls
# ------------------------------------------------------------------

# 1) Run Gradle, which will place $(PACKED) in build/dist
$(PACKED):
	@echo ">> building single‑file exe with Gradle"
	$(GRADLE) --no-daemon --console=plain warpPack

# 2) Copy / rename exactly as OpenBench requested
$(EXE): $(PACKED)
	@echo ">> copying to $(EXE)"
	cp -f "$(PACKED)" "$(EXE)"
ifdef NEEDS_EXE_EXT
	cp -f "$(PACKED)" "$(EXE).exe"
endif
	@echo ">> done"

# ------------------------------------------------------------------
clean:
	@echo ">> cleaning"
	-$(GRADLE) --no-daemon --console=plain clean
	-rm -f "$(PACKED)" "$(EXE)" "$(EXE).exe"
# ------------------------------------------------------------------
