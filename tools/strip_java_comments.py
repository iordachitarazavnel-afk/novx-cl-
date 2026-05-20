from __future__ import annotations

import argparse
from pathlib import Path


def _ends_with_whitespace(out: list[str]) -> bool:
    if not out:
        return True
    return out[-1].isspace()


def strip_java_comments(src: str) -> str:
    out: list[str] = []
    i = 0
    n = len(src)
    state = "code"  # code|string|char|textblock|linecomment|blockcomment

    while i < n:
        ch = src[i]

        if state == "code":
            if ch == '"' and src.startswith('"""', i):
                out.append('"""')
                i += 3
                state = "textblock"
                continue
            if ch == '"':
                out.append(ch)
                i += 1
                state = "string"
                continue
            if ch == "'":
                out.append(ch)
                i += 1
                state = "char"
                continue
            if ch == "/" and i + 1 < n and src[i + 1] == "/":
                if not _ends_with_whitespace(out):
                    out.append(" ")
                i += 2
                state = "linecomment"
                continue
            if ch == "/" and i + 1 < n and src[i + 1] == "*":
                if not _ends_with_whitespace(out):
                    out.append(" ")
                i += 2
                state = "blockcomment"
                continue

            out.append(ch)
            i += 1
            continue

        if state == "string":
            out.append(ch)
            i += 1
            if ch == "\\" and i < n:
                out.append(src[i])
                i += 1
                continue
            if ch == '"':
                state = "code"
            continue

        if state == "char":
            out.append(ch)
            i += 1
            if ch == "\\" and i < n:
                out.append(src[i])
                i += 1
                continue
            if ch == "'":
                state = "code"
            continue

        if state == "textblock":
            if src.startswith('"""', i):
                out.append('"""')
                i += 3
                state = "code"
                continue
            out.append(ch)
            i += 1
            continue

        if state == "linecomment":
            if ch == "\r":
                out.append("\r")
                i += 1
                if i < n and src[i] == "\n":
                    out.append("\n")
                    i += 1
                state = "code"
                continue
            if ch == "\n":
                out.append("\n")
                i += 1
                state = "code"
                continue
            i += 1
            continue

        if state == "blockcomment":
            if ch == "\r":
                out.append("\r")
                i += 1
                if i < n and src[i] == "\n":
                    out.append("\n")
                    i += 1
                continue
            if ch == "\n":
                out.append("\n")
                i += 1
                continue
            if ch == "*" and i + 1 < n and src[i + 1] == "/":
                i += 2
                state = "code"
                continue
            i += 1
            continue

        raise RuntimeError(f"unknown state: {state}")

    return "".join(out)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    args = parser.parse_args()

    root: Path = args.root
    for path in sorted(root.rglob("*.java")):
        with path.open("r", encoding="utf-8", newline="") as f:
            original = f.read()
        stripped = strip_java_comments(original)
        if stripped != original:
            with path.open("w", encoding="utf-8", newline="") as f:
                f.write(stripped)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
