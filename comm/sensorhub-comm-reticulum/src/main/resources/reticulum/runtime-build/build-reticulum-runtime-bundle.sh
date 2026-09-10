#!/usr/bin/env sh
set -eu

# build-reticulum-runtime-bundle
# This recipe creates an OSH-managed packagedPythonRuntime staging tree outside
# source control. It intentionally writes to build/ by default; release bundles
# can be reviewed and copied into src/main/resources/reticulum/runtime only
# after SBOM/license/native-library checks pass.

PLATFORM="${1:-linux-x86_64}"
PYTHON="${PYTHON:-python3}"
OUT_DIR="${OUT_DIR:-build/reticulum-runtime/${PLATFORM}}"
WHEELHOUSE="${OUT_DIR}/wheelhouse"
PYTHON_DIR="${OUT_DIR}/python"

mkdir -p "${WHEELHOUSE}" "${PYTHON_DIR}"

"${PYTHON}" - <<'PY'
import sys
if sys.version_info < (3, 11) or sys.version_info >= (3, 14):
    raise SystemExit("Python runtime must satisfy >=3.11,<3.14")
PY

"${PYTHON}" -m pip download --only-binary=:all: --dest "${WHEELHOUSE}" \
    numpy==2.3.4 \
    pycodec2==4.1.0 \
    cffi==2.0.0

if "${PYTHON}" - <<'PY'
import sys
raise SystemExit(0 if sys.version_info >= (3, 13) else 1)
PY
then
    "${PYTHON}" -m pip download --only-binary=:all: --dest "${WHEELHOUSE}" audioop-lts==0.2.1
fi

cat > "${OUT_DIR}/RETICULUM-RUNTIME-BUNDLE-MANIFEST.json" <<EOF
{
  "platform": "${PLATFORM}",
  "packagedPythonRuntime": "pending-python-distribution-copy",
  "embeddedWheelhouse": "${WHEELHOUSE}",
  "policy": "do-not-use-system-site-packages"
}
EOF

printf '%s\n' "Runtime dependency wheelhouse staged at ${WHEELHOUSE}"
printf '%s\n' "Copy a reviewed portable Python distribution into ${PYTHON_DIR} before release packaging."
