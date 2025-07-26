# --- Minimal Makefile: just call the batch script -----------------
EXE ?= Helios.exe            # OpenBench passes EXE=Helios‑<sha>[.exe]

.PHONY: all clean

all:
	cmd /c buildandcopy.bat "$(EXE)"

clean:
	cmd /c gradlew.bat --no-daemon clean
