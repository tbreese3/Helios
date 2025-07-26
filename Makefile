# ----------  MINIMAL WINDOWS MAKEFILE  ----------
# Usage:
#   make EXE=Helios-<sha>     (OpenBench does this)
#   make                      (defaults to Helios.exe)

EXE ?= Helios.exe                          # target file name
PACKED = build\dist\Helios.exe             # produced by warpPack

.PHONY: all
all:
	# build and copy in one Windows command
	cmd /c " \
		call gradlew.bat --no-daemon --console=plain warpPack && \
		if not exist $(PACKED) (echo Build failed & exit /b 1) && \
		copy /Y $(PACKED) $(EXE) >nul && \
		if /I not \"$(EXE:~-4)\"==\".exe\" copy /Y $(PACKED) $(EXE).exe >nul \
	"

# optional cleanup
.PHONY: clean
clean:
	cmd /c "call gradlew.bat --no-daemon clean & del /Q $(PACKED) $(EXE) $(EXE).exe 2>nul"
