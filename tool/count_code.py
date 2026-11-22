"""[INPUT]: 从项目根目录或指定目录读取代码和部署配置。
[OUTPUT]: 按类型输出文件数、总行数、空行数和非空行数。
[POS]: 本机只读统计入口；入口和子目录共用排除规则。
[PROTOCOL]: Update this header on change, then check AGENTS.md
"""

import argparse
import os
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator


PROJECT_ROOT = Path(__file__).resolve().parents[1]
EXCLUDED_DIRECTORIES = {
    ".cache", ".git", ".idea", ".state", ".tools", ".venv", "__pycache__",
    "artifacts", "backups", "blob-report", "build", "coverage", "dist", "docs",
    "local", "node_modules", "plan", "playwright-report", "rule", "target",
    "test-results", "test-support", "tmp", "venv",
}
EXCLUDED_FILES = {"package-lock.json", "npm-shrinkwrap.json", "pnpm-lock.yaml"}
SUFFIX_TYPES = {
    ".java": "Java",
    ".py": "Python",
    ".ts": "TypeScript",
    ".tsx": "TSX",
    ".js": "JavaScript",
    ".mjs": "JavaScript",
    ".cjs": "JavaScript",
    ".jsx": "JSX",
    ".css": "CSS",
    ".html": "HTML",
    ".sql": "SQL",
    ".sh": "Shell",
    ".ps1": "PowerShell",
    ".bat": "Batch",
    ".cmd": "Batch",
    ".json": "JSON",
    ".yaml": "YAML",
    ".yml": "YAML",
    ".xml": "XML",
    ".properties": "Properties",
    ".factories": "Properties",
    ".toml": "TOML",
}


@dataclass
class Counts:
    files: int = 0
    lines: int = 0
    blank: int = 0

    @property
    def nonblank(self) -> int:
        return self.lines - self.blank


def file_type(path: Path) -> str | None:
    name = path.name.casefold()
    if name in EXCLUDED_FILES:
        return None
    if name == "dockerfile" or name.startswith("dockerfile."):
        return "Dockerfile"
    if name == "mvnw":
        return "Shell"
    if name.startswith(".env"):
        if name.startswith(".env.") and name.endswith(".example"):
            return "Env template"
        return None
    return SUFFIX_TYPES.get(path.suffix.casefold())


def raise_walk_error(error: OSError) -> None:
    raise error


def code_files(root: Path) -> Iterator[tuple[Path, str]]:
    root = root.resolve()
    scope = root.relative_to(PROJECT_ROOT) if root.is_relative_to(PROJECT_ROOT) else root
    excluded = next((part for part in scope.parts if part.casefold() in EXCLUDED_DIRECTORIES), None)
    if excluded:
        raise ValueError(f"入口位于排除目录 {excluded}：{root}")
    for directory, subdirectories, filenames in os.walk(root, onerror=raise_walk_error):
        subdirectories[:] = [
            name for name in subdirectories if name.casefold() not in EXCLUDED_DIRECTORIES
        ]
        for name in filenames:
            path = Path(directory) / name
            category = file_type(path)
            if category and not path.is_symlink() and path.is_file():
                yield path, category


def count_code(root: Path) -> dict[str, Counts]:
    totals: dict[str, Counts] = defaultdict(Counts)
    for path, category in code_files(root):
        count = totals[category]
        with path.open(encoding="utf-8-sig") as source:
            count.files += 1
            for line in source:
                count.lines += 1
                if not line.strip():
                    count.blank += 1
    return totals


def print_counts(totals: dict[str, Counts]) -> None:
    header = f"{'Type':<15} {'Files':>8} {'Lines':>10} {'Blank':>10} {'Nonblank':>10}"
    print(header)
    print("-" * len(header))
    total = Counts()
    rows = sorted(totals.items(), key=lambda item: (-item[1].lines, item[0]))
    for category, count in rows:
        print(
            f"{category:<15} {count.files:>8} {count.lines:>10} "
            f"{count.blank:>10} {count.nonblank:>10}"
        )
        total.files += count.files
        total.lines += count.lines
        total.blank += count.blank
    print("-" * len(header))
    print(f"{'TOTAL':<15} {total.files:>8} {total.lines:>10} {total.blank:>10} {total.nonblank:>10}")


def main() -> None:
    parser = argparse.ArgumentParser(description="按类型统计文件和物理行数（非空行含注释）。")
    parser.add_argument(
        "directory", nargs="?", type=Path, default=PROJECT_ROOT, help="统计目录，默认项目根目录"
    )
    arguments = parser.parse_args()
    root = arguments.directory
    if not root.is_dir():
        parser.error(f"不是目录：{root}")
    try:
        print_counts(count_code(root))
    except (OSError, ValueError) as error:
        parser.exit(1, f"统计失败：{error}\n")


if __name__ == "__main__":
    main()
