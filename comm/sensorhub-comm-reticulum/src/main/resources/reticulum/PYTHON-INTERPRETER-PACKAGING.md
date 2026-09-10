# packagedPythonRuntime

This driver must not require an operator-installed Reticulum, LXMF, or LXST
package. The current staged-source smoke uses `python3` only as verification
evidence. A release-ready packaged runtime must provide an OSH-managed
interpreter and embeddedWheelhouse content for each supported desktop platform.

Required runtime layout after resource staging:

- `reticulum/runtime/<platform>/python/bin/python3` for Linux and macOS.
- `reticulum/runtime/<platform>/python/python.exe` for Windows.
- `reticulum/runtime/<platform>/wheelhouse/` with pinned wheels or unpacked
  packages for `numpy`, `pycodec2`, `cffi`, and `audioop-lts` where required.

The harness distinguishes this packagedPythonRuntime contract from the current
system-Python smoke. Until runtime platform bundles are present, packaged
runtime checks must fail closed and must not be reported as full
self-sufficiency.
