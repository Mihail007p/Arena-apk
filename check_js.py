"""Validate every JavaScript snippet injected by MainActivity before building.

A single malformed concatenated string silently kills the whole injected
script inside WebView (that is exactly what broke experiment 1.4), so we run
`node --check` on each constant as part of build.sh.
"""
import json
import pathlib
import re
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
SOURCE = HERE / "src/ai/arena/app/MainActivity.java"
NAMES = [
    "DARK_JS",
    "DOWNLOAD_JS",
]

source = SOURCE.read_text(encoding="utf-8")
failed = False

for name in NAMES:
    match = re.search(
        r"private static final String " + name + r"\s*=\s*(.*?);\s*\n",
        source, re.S)
    if not match:
        print(f"  {name}: NOT FOUND in Java source")
        failed = True
        continue
    literals = re.findall(r'"((?:[^"\\]|\\.)*)"', match.group(1))
    js = "".join(json.loads('"' + lit + '"') for lit in literals)
    with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False,
                                    encoding="utf-8") as tmp:
        tmp.write(js)
        path = tmp.name
    result = subprocess.run(["node", "--check", path],
                            capture_output=True, text=True)
    pathlib.Path(path).unlink(missing_ok=True)
    if result.returncode == 0:
        print(f"  {name}: OK ({len(js)} chars)")
    else:
        print(f"  {name}: SYNTAX ERROR")
        print(result.stderr.strip()[:600])
        failed = True

if failed:
    sys.exit(1)
print("  all injected JavaScript is valid")
