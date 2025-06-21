#!/usr/bin/env bash
set -euo pipefail

# ----------------------------
# Build Helios with the wrapper
# ----------------------------
./gradlew clean jpackage               # creates build/jpackage/Helios/Helios (Linux) or .exe on Windows

# ---------------------------------------
# Move the binary to the repo’s top level
# ---------------------------------------
if [[ -f build/jpackage/Helios/Helios ]]; then
    mv -f build/jpackage/Helios/Helios     ./Helios
elif [[ -f build/jpackage/Helios/Helios.exe ]]; then
    mv -f build/jpackage/Helios/Helios.exe ./Helios
else
    echo "Helios binary not found after build!" >&2
    exit 1
fi

chmod +x ./Helios
echo "✅ Helios build finished"
