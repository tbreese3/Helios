# ─────────── Helios Makefile (Windows, JIT) ───────────
APP_NAME := Helios
GRADLE   := gradlew.bat
EXE     ?= $(APP_NAME).exe
PACKED   := build\dist\Helios.exe        # output of warpPack

.PHONY: all clean

all: $(EXE)

$(PACKED):
	@echo ">> building single‑file exe with Gradle"
	cmd /c $(GRADLE) --no-daemon --console=plain warpPack

$(EXE): $(PACKED)
	@echo ">> copying to $(EXE)"
	cmd /c copy /Y "$(PACKED)" "$(EXE)" >nul

clean:
	@echo ">> cleaning"
	-@cmd /c $(GRADLE) --no-daemon --console=plain clean
	-@cmd /c del /Q "$(PACKED)" 2>nul
	-@cmd /c del /Q "$(EXE)"    2>nul
