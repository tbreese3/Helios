#!/usr/bin/env bash
set -e

# 1  build the native package
./gradlew --no-daemon clean jpackage

# 2  find the output directory (same on Linux & Mac; .exe on Windows)
PKG_DIR="build/jpackage/Helios"
BIN_SRC="$PKG_DIR/Helios"          # add .exe if [[ $OS == Windows ]]

# 3  copy the entire runtime image to the worker's bindir
cp -r "$PKG_DIR"/* "$BINDIR"

# 4  tell OpenBench where the executable lives
echo "EXECUTABLE $BINDIR/Helios"