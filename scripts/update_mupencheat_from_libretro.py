#!/usr/bin/env python3
"""Merge Libretro N64 .cht files into Mupen64Plus mupencheat.txt.

Libretro's Nintendo 64 .cht files do not include the two-part ROM CRC used by
mupencheat.txt, so this script updates existing CRC sections by matching game
titles. It appends missing fixed-value cheats and preserves existing Mupen
cheats, including richer ????? option lists that Libretro .cht files cannot
represent.
"""

from __future__ import annotations

import argparse
import html
import re
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path


DEFAULT_MUPENCHEAT = Path(
    "third_party/mupen64plus-libretro-nx/mupen64plus-core/data/mupencheat.txt"
)
DEFAULT_ROMDB = Path(
    "third_party/mupen64plus-libretro-nx/mupen64plus-core/data/mupen64plus.ini"
)
DEFAULT_LIBRETRO_CHT = Path(
    "/home/izzy-kauffman/Downloads/libretro-database-master/cht/Nintendo - Nintendo 64"
)

CHEAT_KV_RE = re.compile(r"^cheat(\d+)_(desc|code|enable)\s*=\s*(.*)$")
CODE_LINE_RE = re.compile(r"^[0-9A-Fa-f]{8}\s+[0-9A-Fa-f]{4}$")
TAG_RE = re.compile(r"<[^>]+>")
LOW_VALUE_CHEAT_NAME_RE = re.compile(
    r"#\d+\b|"
    r"\b("
    r"activator|"
    r"cheat\s*device|"
    r"game\s*shark|gameshark|"
    r"xplorer|xploder|"
    r"joker|"
    r"modif\w*|"
    r"keycode"
    r")\b|"
    r"\b(see above note|use only one|must be on)\b",
    re.IGNORECASE,
)


@dataclass
class LibretroCheat:
    name: str
    code_lines: list[str]


@dataclass
class CheatFile:
    path: Path
    title: str
    declared_count: int | None
    cheats: list[LibretroCheat]


@dataclass
class MupenSection:
    title: str = ""
    lines: list[str] = field(default_factory=list)


@dataclass
class RomEntry:
    title: str
    crc_key: str
    country_code: str | None


@dataclass
class Stats:
    cht_files: int = 0
    skipped_large_files: int = 0
    cht_cheats: int = 0
    skipped_variable: int = 0
    skipped_invalid: int = 0
    skipped_empty: int = 0
    skipped_low_value_name: int = 0
    sections: int = 0
    matched_sections: int = 0
    updated_sections: int = 0
    added_cheats: int = 0
    unmatched_sections: int = 0
    created_sections: int = 0


def decode_value(raw: str) -> str:
    raw = raw.strip()
    if len(raw) >= 2 and raw[0] == '"' and raw[-1] == '"':
        raw = raw[1:-1]
    return raw.replace(r"\"", '"').replace(r"\\", "\\")


def clean_title(value: str) -> str:
    value = re.sub(r"\[[^\]]+]", " ", value)
    value = re.sub(r"\((?:M\d+|En(?:,[A-Za-z]{2})*)\)", " ", value, flags=re.IGNORECASE)
    value = unicodedata.normalize("NFKD", value).encode("ascii", "ignore").decode("ascii")
    value = value.lower()
    value = value.replace("&", " and ")
    value = value.replace("_", " ")
    value = value.replace("'", "")
    value = value.replace(".", "")
    value = value.replace("teneighty", "1080")
    value = re.sub(r"\bthe\b", " ", value)
    value = re.sub(r"\b(u|usa)\b", " usa ", value)
    value = re.sub(r"\b(e|europe)\b", " europe ", value)
    value = re.sub(r"\b(j|japan)\b", " japan ", value)
    value = re.sub(r"\brev(?:ision)?\s*([0-9]+)\b", lambda m: f" v1{m.group(1)} ", value)
    value = re.sub(r"\bv(?:ersion)?\.?\s*([0-9]+)\.([0-9]+)\b", r" v\1\2 ", value)
    value = re.sub(r"\bver\s*([0-9]+)\.([0-9]+)\b", r" v\1\2 ", value)
    value = re.sub(r"[^a-z0-9]+", " ", value)
    words = [
        word
        for word in value.split()
        if word not in {"en", "fr", "de", "es", "it", "ja", "nl", "da", "m3", "m4"}
    ]
    return " ".join(words)


def title_keys(value: str) -> set[str]:
    key = clean_title(value)
    keys = {key, key.replace(" ", "")}
    return {candidate for candidate in keys if candidate}


def rom_database_title_keys(value: str) -> set[str]:
    keys = title_keys(value)
    key = clean_title(value)
    words = key.split()
    if "v10" in words:
        without_initial_version = " ".join(word for word in words if word != "v10")
        if without_initial_version:
            keys.add(without_initial_version)
            keys.add(without_initial_version.replace(" ", ""))
    return {candidate for candidate in keys if candidate}


def clean_cheat_name(value: str) -> str:
    value = html.unescape(value)
    value = value.replace("<BR>", "\\").replace("<br>", "\\").replace("<br/>", "\\")
    value = TAG_RE.sub("", value)
    value = value.replace("\r", " ").replace("\n", " ")
    return re.sub(r"\s+", " ", value).strip()


def cheat_name_key(value: str) -> str:
    value = clean_cheat_name(value).lower().replace("\\", " ")
    value = re.sub(r"[^a-z0-9]+", " ", value)
    return " ".join(value.split())


def code_key(lines: list[str]) -> str:
    return " ".join(line.upper() for line in lines)


def parse_code_lines(code: str) -> tuple[list[str], str | None]:
    if re.search(r"\bX{4}\b|\?{4}", code, flags=re.IGNORECASE):
        return [], "variable"

    lines: list[str] = []
    for part in re.split(r"[;+]", code):
        part = re.sub(r"\s+", " ", part.strip()).upper()
        if not part:
            continue
        if not CODE_LINE_RE.fullmatch(part):
            return [], "invalid"
        lines.append(part)

    if not lines:
        return [], "empty"
    return lines, None


def parse_declared_cheat_count(path: Path) -> int | None:
    with path.open(errors="replace") as source:
        for line in source:
            match = re.match(r"^cheats\s*=\s*(\d+)", line.strip())
            if match:
                return int(match.group(1))
            if line.strip():
                return None
    return None


def parse_libretro_file(path: Path, declared_count: int | None, stats: Stats) -> CheatFile:
    entries: dict[int, dict[str, str]] = {}
    for line in path.read_text(errors="replace").splitlines():
        match = CHEAT_KV_RE.match(line.strip())
        if not match:
            continue
        index = int(match.group(1))
        field_name = match.group(2)
        entries.setdefault(index, {})[field_name] = decode_value(match.group(3))

    cheats: list[LibretroCheat] = []
    for index in sorted(entries):
        entry = entries[index]
        name = clean_cheat_name(entry.get("desc", ""))
        code = entry.get("code", "").strip()
        if not name or not code:
            stats.skipped_empty += 1
            continue
        if LOW_VALUE_CHEAT_NAME_RE.search(name):
            stats.skipped_low_value_name += 1
            continue

        code_lines, reason = parse_code_lines(code)
        if reason == "variable":
            stats.skipped_variable += 1
            continue
        if reason == "invalid":
            stats.skipped_invalid += 1
            continue
        if reason == "empty":
            stats.skipped_empty += 1
            continue

        cheats.append(LibretroCheat(name=name, code_lines=code_lines))

    stats.cht_files += 1
    stats.cht_cheats += len(cheats)
    return CheatFile(path=path, title=path.stem, declared_count=declared_count, cheats=cheats)


def load_libretro_cheats(
    cht_dir: Path,
    stats: Stats,
    max_cheats_to_parse: int,
    title_filters: list[str],
) -> dict[str, list[CheatFile]]:
    by_key: dict[str, list[CheatFile]] = {}
    for path in sorted(cht_dir.glob("*.cht")):
        if title_filters and not title_matches_filters(path.stem, title_filters):
            continue
        declared_count = parse_declared_cheat_count(path)
        if max_cheats_to_parse > 0 and declared_count is not None and declared_count > max_cheats_to_parse:
            stats.skipped_large_files += 1
            continue
        cheat_file = parse_libretro_file(path, declared_count, stats)
        if not cheat_file.cheats:
            continue
        for key in title_keys(cheat_file.title):
            by_key.setdefault(key, []).append(cheat_file)
    return by_key


def title_matches_filters(title: str, filters: list[str]) -> bool:
    normalized_title = clean_title(title)
    compact_title = normalized_title.replace(" ", "")
    for title_filter in filters:
        normalized_filter = clean_title(title_filter)
        if not normalized_filter:
            continue
        if normalized_filter in normalized_title:
            return True
        if normalized_filter.replace(" ", "") in compact_title:
            return True
    return False


def load_rom_database(path: Path) -> dict[str, list[RomEntry]]:
    by_key: dict[str, list[RomEntry]] = {}
    current_title: str | None = None
    current_crc: str | None = None

    def flush() -> None:
        nonlocal current_title, current_crc
        if current_title and current_crc and is_supported_rom_entry(current_title):
            crc_key = crc_key_from_rom_database(current_crc)
            if crc_key:
                entry = RomEntry(
                    title=display_title_from_good_name(current_title),
                    crc_key=crc_key,
                    country_code=country_code_from_title(current_title),
                )
                for key in rom_database_title_keys(current_title):
                    by_key.setdefault(key, []).append(entry)
        current_title = None
        current_crc = None

    for raw_line in path.read_text(errors="replace").splitlines():
        line = raw_line.strip()
        if line.startswith("[") and line.endswith("]"):
            flush()
        elif line.startswith("GoodName="):
            current_title = line.split("=", 1)[1].strip()
        elif line.startswith("CRC="):
            current_crc = line.split("=", 1)[1].strip()

    flush()
    return by_key


def is_supported_rom_entry(title: str) -> bool:
    bracket_tags = re.findall(r"\[([^\]]+)]", title)
    if any(tag != "!" for tag in bracket_tags):
        return False
    return not re.search(r"\b(hack|trainer|bad dump|overdump)\b", title, re.IGNORECASE)


def crc_key_from_rom_database(value: str) -> str | None:
    hex_digits = re.sub(r"[^0-9A-Fa-f]", "", value).upper()
    if len(hex_digits) < 16:
        return None
    return f"{hex_digits[:8]}-{hex_digits[8:16]}"


def country_code_from_title(title: str) -> str | None:
    normalized = clean_title(title)
    words = set(normalized.split())
    if "usa" in words:
        return "45"
    if "europe" in words:
        return "50"
    if "japan" in words:
        return "4A"
    return None


def display_title_from_good_name(title: str) -> str:
    title = re.sub(r"\s*\[[^\]]+]", "", title)
    title = re.sub(r"\s*\(M\d+\)", "", title)
    title = title.replace("(V1.", "(v1.")
    return title.strip()


def parse_mupen_sections(text: str) -> tuple[list[str], list[MupenSection]]:
    header: list[str] = []
    sections: list[MupenSection] = []
    current: MupenSection | None = None

    for line in text.splitlines():
        if line.startswith("crc "):
            if current is not None:
                sections.append(current)
            current = MupenSection(lines=[line])
            continue

        if current is None:
            header.append(line)
            continue

        current.lines.append(line)
        if line.startswith("gn "):
            current.title = line[3:].strip()

    if current is not None:
        sections.append(current)
    return header, sections


def existing_cheat_keys(section: MupenSection) -> tuple[set[str], set[str]]:
    names: set[str] = set()
    codes: set[str] = set()
    current_code_lines: list[str] = []

    def flush_code() -> None:
        if current_code_lines:
            codes.add(code_key(current_code_lines))
            current_code_lines.clear()

    for line in section.lines:
        stripped = line.strip()
        if stripped.startswith("cn "):
            flush_code()
            names.add(cheat_name_key(stripped[3:].strip()))
        elif stripped.startswith("crc ") or stripped.startswith("gn "):
            flush_code()
        elif stripped.startswith("cd ") or stripped.startswith("//") or not stripped:
            continue
        elif CODE_LINE_RE.fullmatch(re.sub(r"\s+", " ", stripped).upper()):
            current_code_lines.append(re.sub(r"\s+", " ", stripped).upper())

    flush_code()
    return names, codes


def matching_cheat_files(section: MupenSection, by_key: dict[str, list[CheatFile]]) -> list[CheatFile]:
    seen_paths: set[Path] = set()
    matches: list[CheatFile] = []
    for key in title_keys(section.title):
        for cheat_file in by_key.get(key, []):
            if cheat_file.path not in seen_paths:
                seen_paths.add(cheat_file.path)
                matches.append(cheat_file)
    return matches


def merge_sections(
    sections: list[MupenSection],
    by_key: dict[str, list[CheatFile]],
    stats: Stats,
    max_cheats_per_file: int,
) -> None:
    for section in sections:
        stats.sections += 1
        if not section.title:
            stats.unmatched_sections += 1
            continue

        matches = matching_cheat_files(section, by_key)
        if not matches:
            stats.unmatched_sections += 1
            continue

        stats.matched_sections += 1
        existing_names, existing_codes = existing_cheat_keys(section)
        added: list[tuple[LibretroCheat, Path]] = []
        added_names: set[str] = set()
        added_codes: set[str] = set()

        for cheat_file in matches:
            if is_over_cheat_limit(cheat_file, max_cheats_per_file):
                continue
            for cheat in cheat_file.cheats:
                name_key = cheat_name_key(cheat.name)
                codes_key = code_key(cheat.code_lines)
                if not name_key or not codes_key:
                    continue
                if name_key in existing_names or name_key in added_names:
                    continue
                if codes_key in existing_codes or codes_key in added_codes:
                    continue
                added.append((cheat, cheat_file.path))
                added_names.add(name_key)
                added_codes.add(codes_key)

        if not added:
            continue

        stats.updated_sections += 1
        stats.added_cheats += len(added)
        source_names = sorted({path.name for _, path in added})
        section.lines.append(f"// Libretro database additions: {', '.join(source_names)}")
        for cheat, _ in added:
            section.lines.append(f" cn {cheat.name}")
            for code_line in cheat.code_lines:
                section.lines.append(f"  {code_line}")


def append_missing_sections(
    sections: list[MupenSection],
    by_key: dict[str, list[CheatFile]],
    rom_entries_by_key: dict[str, list[RomEntry]],
    stats: Stats,
    max_cheats_per_file: int,
) -> None:
    existing_crc_keys = {
        crc_key_from_mupen_line(section.lines[0])
        for section in sections
        if section.lines and section.lines[0].startswith("crc ")
    }
    added_crc_keys: set[str] = set()

    cheat_files = {
        cheat_file.path: cheat_file
        for files in by_key.values()
        for cheat_file in files
        if not is_over_cheat_limit(cheat_file, max_cheats_per_file)
    }.values()

    for cheat_file in sorted(cheat_files, key=lambda item: item.path.name):
        entries = matching_rom_entries(cheat_file, rom_entries_by_key)
        for entry in entries:
            if entry.crc_key in existing_crc_keys or entry.crc_key in added_crc_keys:
                continue
            sections.append(section_from_libretro(entry, cheat_file))
            added_crc_keys.add(entry.crc_key)
            stats.created_sections += 1


def matching_rom_entries(
    cheat_file: CheatFile,
    rom_entries_by_key: dict[str, list[RomEntry]],
) -> list[RomEntry]:
    seen_crc_keys: set[str] = set()
    entries: list[RomEntry] = []
    for key in title_keys(cheat_file.title):
        for entry in rom_entries_by_key.get(key, []):
            if entry.crc_key not in seen_crc_keys:
                seen_crc_keys.add(entry.crc_key)
                entries.append(entry)
    return entries


def section_from_libretro(entry: RomEntry, cheat_file: CheatFile) -> MupenSection:
    crc_line = f"crc {entry.crc_key}"
    if entry.country_code:
        crc_line = f"{crc_line}-C:{entry.country_code}"
    lines = [
        crc_line,
        f"gn {entry.title}",
        f"// Libretro database additions: {cheat_file.path.name}",
    ]
    for cheat in cheat_file.cheats:
        lines.append(f" cn {cheat.name}")
        for code_line in cheat.code_lines:
            lines.append(f"  {code_line}")
    return MupenSection(title=entry.title, lines=lines)


def crc_key_from_mupen_line(line: str) -> str | None:
    return crc_key_from_rom_database(line.removeprefix("crc").split("-C:", 1)[0])


def is_over_cheat_limit(cheat_file: CheatFile, max_cheats_per_file: int) -> bool:
    return (
        max_cheats_per_file > 0
        and cheat_file.declared_count is not None
        and cheat_file.declared_count > max_cheats_per_file
    )


def render(header: list[str], sections: list[MupenSection]) -> str:
    lines = list(header)
    for section in sections:
        lines.extend(section.lines)
    return "\n".join(lines).rstrip() + "\n"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cht-dir", type=Path, default=DEFAULT_LIBRETRO_CHT)
    parser.add_argument("--mupencheat", type=Path, default=DEFAULT_MUPENCHEAT)
    parser.add_argument("--romdb", type=Path, default=DEFAULT_ROMDB)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument(
        "--max-cheats-per-file",
        type=int,
        default=100,
        help="Skip Libretro .cht files above this declared cheat count; use 0 for no cap.",
    )
    parser.add_argument(
        "--max-cheats-per-new-file",
        type=int,
        default=250,
        help="Allow missing CRC sections to be created from .cht files up to this declared count.",
    )
    parser.add_argument(
        "--create-missing-sections",
        action="store_true",
        help="Create new CRC sections by matching .cht names to mupen64plus.ini.",
    )
    parser.add_argument(
        "--title-filter",
        action="append",
        default=[],
        help="Only process Libretro .cht files whose titles contain this normalized text.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    stats = Stats()

    if not args.cht_dir.is_dir():
        raise SystemExit(f"Libretro cheat directory not found: {args.cht_dir}")
    if not args.mupencheat.is_file():
        raise SystemExit(f"mupencheat.txt not found: {args.mupencheat}")
    if not args.romdb.is_file():
        raise SystemExit(f"mupen64plus.ini not found: {args.romdb}")

    max_cheats_to_parse = max(args.max_cheats_per_file, args.max_cheats_per_new_file)
    by_key = load_libretro_cheats(args.cht_dir, stats, max_cheats_to_parse, args.title_filter)
    rom_entries_by_key = load_rom_database(args.romdb)
    header, sections = parse_mupen_sections(args.mupencheat.read_text(errors="replace"))
    merge_sections(sections, by_key, stats, args.max_cheats_per_file)
    if args.create_missing_sections:
        append_missing_sections(sections, by_key, rom_entries_by_key, stats, args.max_cheats_per_new_file)

    output = args.output or args.mupencheat
    if not args.dry_run:
        output.write_text(render(header, sections))

    print(f"Libretro .cht files scanned: {stats.cht_files}")
    print(f"Libretro .cht files skipped for size: {stats.skipped_large_files}")
    print(f"Usable fixed-value Libretro cheats: {stats.cht_cheats}")
    print(f"Skipped variable XXXX cheats: {stats.skipped_variable}")
    print(f"Skipped invalid/unsupported cheats: {stats.skipped_invalid}")
    print(f"Skipped empty cheats: {stats.skipped_empty}")
    print(f"Skipped low-value helper/modifier cheats: {stats.skipped_low_value_name}")
    print(f"Mupen sections scanned: {stats.sections}")
    print(f"Mupen sections matched by title: {stats.matched_sections}")
    print(f"Mupen sections without a title match: {stats.unmatched_sections}")
    print(f"Mupen sections updated: {stats.updated_sections}")
    print(f"Mupen sections created from ROM database: {stats.created_sections}")
    print(f"Libretro cheats appended: {stats.added_cheats}")
    if args.dry_run:
        print("Dry run only; no file written.")
    else:
        print(f"Updated: {output}")


if __name__ == "__main__":
    main()
