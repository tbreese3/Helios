# -------- Helios Makefile: minimal & Git‑Bash‑friendly --------
GRADLE := ./gradlew                 # the shell‑script wrapper
PACKED := build/dist/Helios.exe     # output of warpPack
EXE    ?= Helios.exe                # OpenBench sets EXE=Helios‑<sha>[.exe]

.PHONY: all clean

# default target
all: $(EXE)

# 1) build single‑file exe
$(PACKED):
	$(GRADLE) --no-daemon --console=plain warpPack

# 2) copy / rename exactly as requested
$(EXE): $(PACKED)
	cp -f $(PACKED) $(EXE)
	# if EXE lacks ".exe", also drop a .exe twin
	echo $(EXE) | grep -qi '\.exe$$' || cp -f $(PACKED) $(EXE).exe

# clean
clean:
	-$(GRADLE) --no-daemon clean
	-rm -f $(PACKED) $(EXE) $(EXE).exe
# -------- Helios Makefile: minimal & Git‑Bash‑friendly --------
GRADLE := ./gradlew                 # the shell‑script wrapper
PACKED := build/dist/Helios.exe     # output of warpPack
EXE    ?= Helios.exe                # OpenBench sets EXE=Helios‑<sha>[.exe]

.PHONY: all clean

# default target
all: $(EXE)

# 1) build single‑file exe
$(PACKED):
	$(GRADLE) --no-daemon --console=plain warpPack

# 2) copy / rename exactly as requested
$(EXE): $(PACKED)
	cp -f $(PACKED) $(EXE)
	# if EXE lacks ".exe", also drop a .exe twin
	echo $(EXE) | grep -qi '\.exe$$' || cp -f $(PACKED) $(EXE).exe

# clean
clean:
	-$(GRADLE) --no-daemon clean
	-rm -f $(PACKED) $(EXE) $(EXE).exe
