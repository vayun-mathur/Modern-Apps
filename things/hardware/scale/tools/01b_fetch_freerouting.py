#!/usr/bin/env python3
"""Phase1b - fetch freerouting jar or document docker alternative."""
import pathlib, subprocess, sys
root = pathlib.Path(__file__).parent
vendor = root / "vendor"
vendor.mkdir(exist_ok=True)
jar_path = vendor / "freerouting.jar"
url = "https://github.com/freerouting/freerouting/releases/download/v2.1.0/freerouting-2.1.0.jar"
if not jar_path.exists():
    print(f"Downloading {url} -> {jar_path}")
    subprocess.run(["curl","-L", url, "-o", str(jar_path)], check=False)
else:
    print(f"Exists {jar_path}")
print("Docker alternative: docker run --rm -v /tmp:/work ghcr.io/freerouting/freerouting:latest -de /work/scale.dsn -do /work/scale.ses -mp 100")
