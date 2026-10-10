# Publishing the `traceveil` CLI to PyPI

Layout: `pyproject.toml` (setuptools, stdlib-only, Python >= 3.11) and `src/traceveil/`. The version comes from `src/traceveil/__init__.py` (`__version__`). Entry point: `traceveil = traceveil.cli:main`.

## Before the first upload
- Check the name is free: https://pypi.org/project/traceveil/ and https://test.pypi.org/project/traceveil/ (a 404 means free).
- Fix `authors` and `[project.urls]` in `pyproject.toml`; `LICENSE` names a single copyright holder, confirm with the team.
- The package long description is `tools/README.md`; it must make sense on the PyPI page (the CLI needs the Traceveil debug APK, built from this repo).
- Create accounts and API tokens on test.pypi.org and pypi.org (2FA required). Prefer a token scoped to the project, or Trusted Publishing from GitHub Actions. Never commit tokens.

## Build and check
```bash
python3 -m venv /tmp/tv-release && . /tmp/tv-release/bin/activate
pip install build twine
rm -rf dist && python -m build          # dist/*.tar.gz and *.whl
twine check dist/*
```

## Clean-venv smoke test (run outside the repo)
```bash
python3 -m venv /tmp/tv-smoke && cd /tmp
/tmp/tv-smoke/bin/pip install /path/to/repo/dist/traceveil-*.whl
/tmp/tv-smoke/bin/traceveil --version && /tmp/tv-smoke/bin/python -m traceveil --help
```

## TestPyPI, then PyPI
```bash
twine upload --repository testpypi dist/*        # username __token__, password = TestPyPI token
python3 -m venv /tmp/tv-test && /tmp/tv-test/bin/pip install --index-url https://test.pypi.org/simple/ traceveil
/tmp/tv-test/bin/traceveil --version

twine upload dist/*                               # production, only after the TestPyPI install works
```
Uploaded versions cannot be replaced: bump `__version__` for every upload, including TestPyPI retries.
