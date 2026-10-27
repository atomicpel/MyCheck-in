"""Командная строка:

    ru-passport scan photo.jpg
    ru-passport mrz "PNRUS..." "4601234565RUS..."
"""

from __future__ import annotations

import argparse
import json
import sys

from .scanner import parse, scan


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="ru-passport", description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("scan", help="распознать фото/скан разворота паспорта")
    s.add_argument("image")
    s.add_argument("--no-viz", action="store_true", help="только MRZ, без визуальной зоны")
    m = sub.add_parser("mrz", help="разобрать две строки MRZ")
    m.add_argument("line1")
    m.add_argument("line2")
    args = p.parse_args(argv)

    data = scan(args.image, with_viz=not args.no_viz) if args.cmd == "scan" else parse(args.line1, args.line2)
    json.dump(data.to_dict(), sys.stdout, ensure_ascii=False, indent=2, default=str)
    print()
    return 0 if data.is_valid else 1


if __name__ == "__main__":
    sys.exit(main())
