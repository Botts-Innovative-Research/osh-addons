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
PYCODEC2_VERSION="4.1.1"
PIP_PLATFORMS=""

case "${PLATFORM}" in
    linux-x86_64)
        PIP_PLATFORMS="manylinux_2_28_x86_64 manylinux_2_27_x86_64 manylinux2014_x86_64 manylinux_2_17_x86_64"
        ;;
    linux-aarch64)
        PIP_PLATFORMS="manylinux_2_28_aarch64 manylinux_2_27_aarch64 manylinux2014_aarch64 manylinux_2_17_aarch64"
        ;;
    macos-aarch64)
        PIP_PLATFORMS="macosx_11_0_arm64"
        ;;
    macos-x86_64)
        PIP_PLATFORMS="macosx_11_0_x86_64"
        PYCODEC2_VERSION="4.1.0"
        ;;
    windows-x86_64)
        PIP_PLATFORMS="win_amd64"
        ;;
    *)
        echo "Unsupported platform ${PLATFORM}" >&2
        exit 2
        ;;
esac

mkdir -p "${WHEELHOUSE}" "${PYTHON_DIR}"

"${PYTHON}" - <<'PY'
import sys
if sys.version_info < (3, 11) or sys.version_info >= (3, 14):
    raise SystemExit("Python runtime must satisfy >=3.11,<3.14")
PY

PLATFORM_ARGS=""
for PIP_PLATFORM in ${PIP_PLATFORMS}; do
    PLATFORM_ARGS="${PLATFORM_ARGS} --platform ${PIP_PLATFORM}"
done

# shellcheck disable=SC2086
"${PYTHON}" -m pip download --only-binary=:all: --dest "${WHEELHOUSE}" \
    ${PLATFORM_ARGS} --python-version 3.12 --implementation cp --abi cp312 \
    numpy==2.3.4 \
    pycodec2==${PYCODEC2_VERSION} \
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
  "pycodec2": "${PYCODEC2_VERSION}",
  "policy": "do-not-use-system-site-packages"
}
EOF

printf '%s\n' "Runtime dependency wheelhouse staged at ${WHEELHOUSE}"
printf '%s\n' "Copy a reviewed portable Python distribution into ${PYTHON_DIR} before release packaging."
