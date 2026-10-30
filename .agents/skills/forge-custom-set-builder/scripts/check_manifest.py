#!/usr/bin/env python3
"""Check a Forge Custom Set Builder source manifest (internal schema v1.0).

Purely structural; does not access network or infer game behavior.
"""
import json
import sys
from pathlib import Path

STATUSES = {"verified", "partial", "needs_review", "unavailable"}
METHODS = {"structured", "html", "browser", "image", "manual"}


def validate(obj):
    errors, warnings = [], []
    if not isinstance(obj, dict):
        return ["Manifest root must be an object"], warnings
    for field in ("set_code", "set_url"):
        if not isinstance(obj.get(field), str) or not obj[field].strip():
            errors.append(f"Missing/non-string {field}")
    cards = obj.get("cards")
    if not isinstance(cards, list):
        return errors + ["cards must be an array"], warnings
    if not cards:
        warnings.append("cards array is empty")
    expected = obj.get("expected_cards")
    if expected is not None:
        if type(expected) is not int or expected < 0:
            errors.append("expected_cards must be a nonnegative integer")
        elif expected != len(cards):
            warnings.append(f"Expected {expected} cards, got {len(cards)}")
    seen = {}
    seen_ids = {}
    seen_forge_names = {}
    for index, card in enumerate(cards, start=1):
        prefix = f"cards[{index}]"
        if not isinstance(card, dict):
            errors.append(f"{prefix}: must be an object")
            continue
        name = card.get("name")
        if not isinstance(name, str) or not name.strip():
            errors.append(f"{prefix}: missing name")
        else:
            normal = name.strip().casefold()
            if normal in seen:
                warnings.append(f"{prefix}: duplicate name {name!r} (also cards[{seen[normal]}]); check multi-face/aliases")
            else:
                seen[normal] = index
        source_id = card.get("source_id")
        if source_id is not None:
            if not isinstance(source_id, str) or not source_id.strip():
                errors.append(f"{prefix}: source_id must be a nonempty string if supplied")
            elif source_id in seen_ids:
                warnings.append(f"{prefix}: repeated source_id {source_id!r}; verify whether this is alternate art")
            else:
                seen_ids[source_id] = index
        forge_name = card.get("forge_name")
        if forge_name is not None:
            if not isinstance(forge_name, str) or not forge_name.strip():
                errors.append(f"{prefix}: forge_name must be a nonempty string if supplied")
            else:
                norm_forge = forge_name.casefold().strip()
                if norm_forge in seen_forge_names:
                    warnings.append(f"{prefix}: repeated forge_name {forge_name!r}; verify card identity and suffix")
                else:
                    seen_forge_names[norm_forge] = index
        for field in ("collector_number", "rarity"):
            val = card.get(field)
            if val is not None and not isinstance(val, str):
                errors.append(f"{prefix}: {field} must be string or null")
        url = card.get("source_url")
        if not isinstance(url, str) or not url.strip():
            warnings.append(f"{prefix}: source_url is missing")
        quality = card.get("quality")
        if not isinstance(quality, dict):
            errors.append(f"{prefix}: missing quality object")
            quality = {}
        status = quality.get("status")
        method = quality.get("method")
        if status not in STATUSES:
            errors.append(f"{prefix}: invalid quality.status {status!r}")
        if method not in METHODS:
            errors.append(f"{prefix}: invalid quality.method {method!r}")
        faces = card.get("faces")
        if not isinstance(faces, list) or not faces:
            errors.append(f"{prefix}: faces must be a nonempty array")
            continue
        if len(faces) > 1:
            warnings.append(f"{prefix}: {len(faces)} faces; verify exact multi-part semantics")
        for j, face in enumerate(faces, start=1):
            pre = f"{prefix}.faces[{j}]"
            if not isinstance(face, dict):
                errors.append(f"{pre}: must be an object")
                continue
            for field in ("name", "type_line"):
                value = face.get(field)
                if not isinstance(value, str) or not value.strip():
                    if status == "unavailable":
                        warnings.append(f"{pre}: missing {field} (unavailable source)")
                    else:
                        errors.append(f"{pre}: missing {field}")
            oracle = face.get("oracle_text")
            if oracle is not None and not isinstance(oracle, str):
                errors.append(f"{pre}: oracle_text must be string or null")
            if oracle is None and status == "verified":
                warnings.append(f"{pre}: verified but oracle_text is unknown; check if the card genuinely has no rules")
            for field in ("mana_cost", "power", "toughness", "loyalty", "defense"):
                if field in face and face[field] is not None and not isinstance(face[field], str):
                    errors.append(f"{pre}: {field} must be string or null")
    return errors, warnings


def main():
    if len(sys.argv) != 2:
        print("Usage: python check_manifest.py <cards.json>", file=sys.stderr)
        return 2
    path = Path(sys.argv[1])
    try:
        obj = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, ValueError) as exc:
        print(f"ERROR: cannot read JSON: {exc}", file=sys.stderr)
        return 2
    errors, warnings = validate(obj)
    for w in warnings:
        print("WARN:", w)
    for e in errors:
        print("ERROR:", e)
    count = len(obj.get("cards", [])) if isinstance(obj, dict) and isinstance(obj.get("cards"), list) else 0
    print(("OK" if not errors else "FAILED") + f": {count} records; {len(errors)} errors, {len(warnings)} warnings")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
