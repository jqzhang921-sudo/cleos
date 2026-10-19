#!/usr/bin/env python3
"""Synchronize public license notices into App assets; --check does not write."""
from pathlib import Path
import argparse

ROOT = Path(__file__).resolve().parents[1]
PAIRS = {
    "LICENSE": "app/src/main/assets/licenses/cleos-apache-2.0.txt",
    "NOTICE": "app/src/main/assets/licenses/cleos-NOTICE.txt",
    "THIRD_PARTY_NOTICES.md": "app/src/main/assets/licenses/third-party-notices.txt",
    "docs/DEPENDENCY_LICENSES.md": "app/src/main/assets/licenses/dependency-licenses.txt",
    "licenses/transfer/journeyapps-zxing-COPYING.txt": "app/src/main/assets/licenses/transfer/journeyapps-zxing-COPYING.txt",
    "licenses/transfer/zxing-core-LICENSE.txt": "app/src/main/assets/licenses/transfer/zxing-core-LICENSE.txt",
    "licenses/transfer/zxing-core-NOTICE.txt": "app/src/main/assets/licenses/transfer/zxing-core-NOTICE.txt",
    "licenses/transfer/scanner-copyrights.txt": "app/src/main/assets/licenses/transfer/scanner-copyrights.txt",
}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify identical copies without editing files")
    args = parser.parse_args()
    stale = []
    for source, target in PAIRS.items():
        data = (ROOT / source).read_bytes()
        path = ROOT / target
        if args.check:
            if not path.is_file() or path.read_bytes() != data:
                stale.append(target)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    if stale:
        parser.exit(1, "License assets out of sync: " + ", ".join(stale) + "\n")
    print("License assets verified." if args.check else "License assets synchronized.")

if __name__ == "__main__":
    main()
