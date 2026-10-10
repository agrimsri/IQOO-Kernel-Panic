"""Traceveil developer CLI: drives an iQOO/Android device over adb and reports network-environment signals."""

__version__ = "0.2.0"

# Process exit codes, also reported in the JSON verdict.
EXIT_PASS = 0
EXIT_FINDINGS = 1
EXIT_INCONCLUSIVE = 2
EXIT_DEVICE = 3
EXIT_USAGE = 4
EXIT_POLICY = 5
EXIT_TIMEOUT = 6
