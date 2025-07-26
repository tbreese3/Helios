# ----- Helios Makefile: call one batch file -------------------
APP_NAME := Helios
EXE     ?= $(APP_NAME).exe

.PHONY: all clean

all:
	buildandcopy.bat "$(EXE)"

clean:
	gradlew.bat --no-daemon clean
