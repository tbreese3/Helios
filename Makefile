# --------- Helios Makefile: just run the batch script ----------
EXE ?= Helios.exe                 # OpenBench passes EXE=Helios-<sha>[.exe]

.PHONY: all clean

all:
	./buildandcopy.bat "$(EXE)"

clean:
	./gradlew.bat --no-daemon clean
