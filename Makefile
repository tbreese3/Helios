# ─────────────── Helios Makefile (minimal) ───────────────
# “make”   →   builds the engine and leaves ./Helios ready
# “make clean” removes build artefacts and the launcher
# ---------------------------------------------------------

# default target ---------------------------------------------------------------
all:
	@echo ">> building with Gradle"
	bash ./gradlew --no-daemon --console=plain installDist

	@echo ">> copying launcher to project root"
	cp -f build/install/Helios/bin/Helios ./Helios

	@echo ">> ensuring executable bit is set"
	chmod +x ./Helios

# explicit alias ---------------------------------------------------------------
build: all

# clean target -----------------------------------------------------------------
clean:
	@echo ">> cleaning"
	bash ./gradlew --no-daemon --console=plain clean
	rm -rf build ./Helios
# ----------------------------------------------------------------------------- 
