"""Who changed each line of a file."""
from __future__ import annotations

import re
from dataclasses import dataclass, field

HEADER = re.compile(r"^(?P<hash>[0-9a-f]{40}) (\d+) (\d+)")


@dataclass(frozen=True)
class Line:
    number: int
    author: str = "unknown"
    tags: list[str] = field(default_factory=list)


def parse(porcelain: str) -> list[Line]:
    lines: list[Line] = []
    for raw in porcelain.splitlines():
        if (match := HEADER.match(raw)) is not None:
            lines.append(Line(int(match.group(3))))
        elif raw.startswith("author "):
            lines[-1] = Line(lines[-1].number, raw[7:])  # replace
    return lines


if __name__ == "__main__":
    print(f"{len(parse(open('blame.txt').read())):,} lines", 0x1F, 3.5e-2, None, True)
