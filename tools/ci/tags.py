#!/usr/bin/env python3
"""CI tag parser for Continuum.

Reads `[tag]` markers from a commit message / PR title+body string and turns
them into a plain dict the CI gate can consume. Closed registry only: anything
inside brackets that we do not know is reported as a warning (with a
did-you-mean hint) and never executed. No eval, no shell interpolation, ever.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import unicodedata
from dataclasses import dataclass
from pathlib import Path

TIER_A = "A"          # cosmetic / info, safe anywhere
TIER_B = "B"          # affects the artifact, ignored on master
TIER_EMERGENCY = "Emergency"  # maintainer escape hatch, audited

MAX_BRACKET_LEN = 200
CHANGELOG_TEXT_MAX = 140

_BRACKET_RE = re.compile(r"\[([^\[\]]{0,%d})\]" % MAX_BRACKET_LEN)
_OVERSIZE_BRACKET_RE = re.compile(r"\[[^\[\]]{%d,}\]" % (MAX_BRACKET_LEN + 1))
_CTRL_RE = re.compile(r"[\x00-\x1f\x7f]")
_WS_RE = re.compile(r"\s+")
_VERSION_TOKEN_RE = re.compile(r"[0-9][0-9a-z.\-]{0,31}")
_REASON_RE = re.compile(r"[a-z0-9][a-z0-9._\-]{0,63}")
_VERSION_LIST_RE = re.compile(r"[0-9a-z.\-]+(,[0-9a-z.\-]+)*")


@dataclass(frozen=True)
class Tag:
    name: str            # stable slug id (docs / tests refer to this)
    inner: str           # canonical inner string as written by the user
    tier: str
    where: str           # human-readable scope (kept in sync with docs)
    effect: str          # one-liner, shown in the docs table
    aliases: tuple[str, ...] = ()
    pattern: str | None = None  # fullmatch regex for value-carrying tags
    ignore_on_master: bool = False
    master_reason: str = "tier B tags are not honored on master"

    @property
    def display(self) -> str:
        return f"[{self.inner}]"


# registry order = docs table order. do not re-sort.
REGISTRY: tuple[Tag, ...] = (
    Tag("ci-skip", "ci skip", TIER_EMERGENCY, "push / PR",
        "nothing builds, nothing runs (native GitHub skip)",
        aliases=("no ci",)),
    Tag("docs-only", "docs only", TIER_A, "dev / PR",
        "markdown/docs lint only; compilation skipped",
        aliases=("only docs",)),
    Tag("build-none", "build none", TIER_A, "dev / PR",
        "lint and parse only, no compilation"),
    Tag("hotfix", "hotfix", TIER_B, "everywhere",
        "forces the hotfix flow (hotfix/* branch, faster release checklist)"),

    Tag("paper-only", "paper only", TIER_B, "dev / PR",
        "build the paper jar only",
        ignore_on_master=True, master_reason="release must be full"),
    Tag("velocity-only", "velocity only", TIER_B, "dev / PR",
        "build the velocity jar only",
        ignore_on_master=True, master_reason="release must be full"),
    Tag("common-only", "common only", TIER_B, "dev / PR",
        "build common/api without the shadowed jars",
        ignore_on_master=True, master_reason="release must be full"),
    Tag("no-folia", "no folia", TIER_B, "dev / PR",
        "skip folia compilation and smoke tests",
        ignore_on_master=True, master_reason="release must be full"),
    Tag("build-full", "build full", TIER_A, "everywhere",
        "force a full build; overrides conflicting build-scope tier B tags"),

    Tag("compat-skip", "compat skip", TIER_A, "dev / PR",
        "compat matrix not run (smoke on latest only)"),
    Tag("compat-full", "compat full", TIER_A, "everywhere",
        "full compat matrix, even on dev pushes"),
    Tag("compat-mc-only", "compat mc=<versions> only", TIER_A, "dev / PR",
        "run only the listed MC versions, comma-separated",
        pattern=r"compat mc=([0-9a-z.,-]+) only"),
    Tag("compat-ignore", "compat ignore mc=<v> reason=<why>", TIER_B, "dev / PR",
        "red matrix cell counts as non-blocking (reason required)",
        pattern=r"compat ignore (.+)",
        ignore_on_master=True, master_reason="master cannot waive a red matrix cell"),
    Tag("compat-java", "compat java=<v>", TIER_A, "dev / PR",
        "limit the toolchain matrix",
        pattern=r"compat java=([0-9a-z.,-]+)"),
    Tag("compat-nightly", "compat nightly", TIER_A, "dev",
        "results marked as nightly; the release table stays untouched"),

    Tag("discord-skip", "discord skip", TIER_A, "dev",
        "no dev build notices in Discord (release announcements unaffected)"),
    Tag("discord-release", "discord release", TIER_B, "dev",
        "also publish this dev build to the release channel",
        ignore_on_master=True,
        master_reason="master reaches the release channel only via the release flow"),
    Tag("no-ping", "no ping", TIER_A, "everywhere",
        "post without @-mentioning the role"),
    Tag("changelog-skip", "changelog skip", TIER_A, "everywhere",
        "commit does not appear in the changelog"),
    Tag("changelog-custom", "changelog: <text>", TIER_A, "everywhere",
        "custom changelog line instead of the commit subject",
        pattern=r"changelog: (.+)"),
    Tag("release-notes", "release notes", TIER_A, "master",
        "long post-release Discord announcement (master merges)"),

    Tag("release", "release", TIER_B, "PR to master",
        "mark this PR as a release candidate (highlighting, full checks)"),
    Tag("no-release", "no release", TIER_B, "master",
        "merge to master without a tag/release job (docs-only zero changes)"),
    Tag("changelog-breaking", "changelog breaking", TIER_A, "everywhere",
        "highlight the entry in the Breaking section"),
    Tag("commits-count-reset", "commits count-reset", TIER_EMERGENCY, "maintainer",
        "recalculate build-<N> (manual, maintainer-only)"),

    Tag("tests-skip", "tests skip", TIER_B, "dev / PR",
        "skip unit tests (smoke remains)",
        ignore_on_master=True, master_reason="master keeps the full test suite"),
    Tag("lint-skip", "lint skip", TIER_A, "dev / PR",
        "skip lint"),
    Tag("flaky-retry", "flaky retry=<n>", TIER_A, "everywhere",
        "re-run failed test jobs up to n times",
        pattern=r"flaky retry=([0-9]{1,2})"),

    Tag("ci-debug", "ci debug", TIER_A, "everywhere",
        "step debug logging on this push"),
    Tag("tags-audit", "tags audit", TIER_A, "everywhere",
        "force the tags-audit job (usage statistics)"),
)


def tag_aliases(tag: Tag) -> tuple[str, ...]:
    """Explicit aliases + the muscle-memory `skip X` ≡ `X skip` rule."""
    out = list(tag.aliases)
    if tag.inner.endswith(" skip"):
        auto = f"skip {tag.inner[:-5]}"
        if auto not in out:
            out.append(auto)
    return tuple(out)


def _build_lookup():
    simple, patterns = {}, []
    for tag in REGISTRY:
        if tag.pattern:
            patterns.append((re.compile(tag.pattern, re.IGNORECASE), tag))
        else:
            simple[tag.inner] = tag
        for alias in tag_aliases(tag):
            simple[alias] = tag
    return simple, patterns


_SIMPLE_LOOKUP, _PATTERN_LOOKUP = _build_lookup()
_CANDIDATES = sorted(set(_SIMPLE_LOOKUP) | {t.inner for t in REGISTRY})


def levenshtein(a: str, b: str) -> int:
    if a == b:
        return 0
    if len(a) < len(b):
        a, b = b, a
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(cur[-1] + 1, prev[j] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def did_you_mean(inner: str) -> str | None:
    best, best_d = None, MAX_BRACKET_LEN + 1
    for cand in _CANDIDATES:
        d = levenshtein(inner, cand)
        if d < best_d:
            best, best_d = cand, d
    if best is not None and best_d <= 3 and best_d <= len(inner) // 2 + 1:
        return best
    return None


def clean_bracket(raw: str) -> str:
    """Strip control chars and collapse whitespace, keeping the original case.

    The tag *key* is matched case-insensitively later, but free-text payloads
    (e.g. [changelog: ...]) keep what the author wrote.
    """
    s = unicodedata.normalize("NFKC", raw)
    s = _CTRL_RE.sub(" ", s)
    return _WS_RE.sub(" ", s).strip()


def _version_list(value: str) -> list[str] | None:
    value = value.lower()
    if not _VERSION_LIST_RE.fullmatch(value):
        return None
    parts = [p for p in value.split(",")]
    if any(not _VERSION_TOKEN_RE.fullmatch(p) for p in parts):
        return None
    return parts


def _validate_values(tag: Tag, m: re.Match) -> tuple[dict, list[str]]:
    """Validate values of parameterized tags.

    Returns (kwargs, warnings). Contract: any warning means the tag is NOT
    honored — no half-applied state, no partial trust.
    """
    if tag.name == "compat-mc-only":
        versions = _version_list(m.group(1))
        if versions is None:
            return {}, [f"[{tag.inner}] got a bad version list {m.group(1)!r}"]
        return {"mc": versions}, []
    if tag.name == "compat-java":
        versions = _version_list(m.group(1))
        if versions is None:
            return {}, [f"[{tag.inner}] got a bad version list {m.group(1)!r}"]
        return {"java": versions}, []
    if tag.name == "flaky-retry":
        n = int(m.group(1))
        if not 1 <= n <= 10:
            return {}, [f"[{tag.inner}] retry must be 1..10, got {n}"]
        return {"n": n}, []
    if tag.name == "changelog-custom":
        text = _WS_RE.sub(" ", m.group(1)).strip()
        if len(text) > CHANGELOG_TEXT_MAX:
            return {}, [f"[{tag.inner}] text is {len(text)} chars, max {CHANGELOG_TEXT_MAX} — shorten it"]
        return {"text": text}, []
    if tag.name == "compat-ignore":
        return _parse_compat_ignore(m.group(1).lower(), tag)
    return {}, [f"[{tag.name}] has no value parser (bug)"]


def _parse_compat_ignore(rest: str, tag: Tag) -> tuple[dict, list[str]]:
    out: dict[str, str] = {}
    for tok in rest.split(" "):
        if "=" not in tok:
            return {}, [f"[{tag.inner}] expected key=value, got {tok!r}"]
        key, value = tok.split("=", 1)
        if key not in ("mc", "reason"):
            return {}, [f"[{tag.inner}] unknown key {key!r} (allowed: mc, reason)"]
        if key in out:
            return {}, [f"[{tag.inner}] duplicate key {key!r}"]
        out[key] = value
    mc = out.get("mc")
    if mc is None or _VERSION_TOKEN_RE.fullmatch(mc) is None:
        return {}, [f"[{tag.inner}] mc=<v> is required and must be a version, got {mc!r}"]
    reason = out.get("reason")
    if not reason:
        return {}, [f"[{tag.inner}] reason=<why> is required (tell us why the cell is non-blocking)"]
    if _REASON_RE.fullmatch(reason) is None:
        return {}, [f"[{tag.inner}] reason must be a short slug (a-z 0-9 . _ -), got {reason!r}"]
    return {"mc": mc, "reason": reason}, []


def classify_ref(ref: str | None) -> str:
    if not ref:
        return "dev"
    r = ref.strip().lower()
    if r.startswith("refs/heads/"):
        r = r[len("refs/heads/"):]
    return "master" if r == "master" else "dev"


def _fresh_result() -> dict:
    return {
        "build": "full",             # full | paper | velocity | common | none
        "folia": True,
        "build_full": False,
        "docs_only": False,
        "hotfix": False,
        "compat": "default",         # default | skip | full | only | nightly
        "compat_mc": [],
        "compat_java": [],
        "compat_ignore": [],
        "discord": "default",        # default | skip | release
        "no_ping": False,
        "changelog_skip": False,
        "changelog_text": None,
        "changelog_breaking": False,
        "release_notes": False,
        "release_candidate": False,
        "no_release": False,
        "tests": "full",             # full | skip
        "lint": "full",              # full | skip
        "flaky_retry": 0,
        "ci_skip": False,
        "ci_debug": False,
        "tags_audit": False,
        "commits_count_reset": False,
        "honored": [],
        "ignored": [],
        "notes": [],
        "warnings": [],
    }


def _apply(res: dict, tag: Tag, kw: dict) -> None:
    n = tag.name
    if n == "ci-skip":              res["ci_skip"] = True
    elif n == "docs-only":          res["docs_only"] = True
    elif n == "build-none":         res["build"] = "none"
    elif n == "hotfix":             res["hotfix"] = True
    elif n == "paper-only":         res["build"] = "paper"
    elif n == "velocity-only":      res["build"] = "velocity"
    elif n == "common-only":        res["build"] = "common"
    elif n == "no-folia":           res["folia"] = False
    elif n == "build-full":         res["build_full"] = True
    elif n == "compat-skip":        res["compat"] = "skip"
    elif n == "compat-full":        res["compat"] = "full"
    elif n == "compat-mc-only":     res["compat"] = "only"; res["compat_mc"] = kw["mc"]
    elif n == "compat-ignore":      res["compat_ignore"].append({"mc": kw["mc"], "reason": kw["reason"]})
    elif n == "compat-java":        res["compat_java"] = kw["java"]
    elif n == "compat-nightly":     res["compat"] = "nightly"
    elif n == "discord-skip":       res["discord"] = "skip"
    elif n == "discord-release":    res["discord"] = "release"
    elif n == "no-ping":            res["no_ping"] = True
    elif n == "changelog-skip":     res["changelog_skip"] = True
    elif n == "changelog-custom":   res["changelog_text"] = kw["text"]
    elif n == "changelog-breaking": res["changelog_breaking"] = True
    elif n == "release-notes":      res["release_notes"] = True
    elif n == "release":            res["release_candidate"] = True
    elif n == "no-release":         res["no_release"] = True
    elif n == "commits-count-reset": res["commits_count_reset"] = True
    elif n == "tests-skip":         res["tests"] = "skip"
    elif n == "lint-skip":          res["lint"] = "skip"
    elif n == "flaky-retry":        res["flaky_retry"] = kw["n"]
    elif n == "ci-debug":           res["ci_debug"] = True
    elif n == "tags-audit":         res["tags_audit"] = True


def parse(source: str | None, ref: str | None = None, fork: bool = False) -> dict:
    """Parse tag markers out of `source` (commit message / PR text).

    `ref` is the branch the pipeline runs on (master gets tier-B filtering);
    `fork=True` marks untrusted fork-PR context, where only tier A is honored.
    Returns a structured dict; `honored` / `ignored` / `notes` / `warnings`
    are report strings intended for the gate summary, nothing is executed.
    """
    res = _fresh_result()
    is_master = classify_ref(ref) == "master"
    source = source or ""

    for oversize in _OVERSIZE_BRACKET_RE.finditer(source):
        res["warnings"].append(f"[...{len(oversize.group(0)) - 2} chars...] bracket group is too long — not honored")

    for m in _BRACKET_RE.finditer(source):
        raw = m.group(1)
        inner = clean_bracket(raw)
        if not inner:
            res["warnings"].append("[ ] empty tag — not honored")
            continue

        tag, pattern_match = _match_tag(inner)
        if tag is None:
            hint = did_you_mean(inner.lower())
            if hint:
                res["warnings"].append(f"[{inner}] unknown tag — not honored. did you mean '[{hint}]'?")
            else:
                res["warnings"].append(f"[{inner}] unknown tag — not honored")
            continue

        if fork and tag.tier != TIER_A:
            continue  # untrusted context: tier A only, dropped silently
        if is_master and tag.ignore_on_master:
            res["ignored"].append(tag.display)
            res["notes"].append(f"{tag.display} ignored on master: {tag.master_reason}")
            continue

        kw: dict = {}
        if pattern_match is not None:
            validated, val_warnings = _validate_values(tag, pattern_match)
            if val_warnings:
                res["warnings"].extend(val_warnings)
                continue
            kw = validated
        _apply(res, tag, kw)
        res["honored"].append(tag.display)
        res["notes"].append(f"{tag.display} honored")

    # post-pass: [build full] (tier A) outranks B build-scope tags, any order
    if res["build_full"]:
        for scope_tag in ("[paper only]", "[velocity only]", "[common only]", "[build none]"):
            if scope_tag in res["honored"]:
                res["notes"].append(f"[build full] overrides {scope_tag}")
        res["build"] = "full"

    if "[release]" in res["honored"] and "[no release]" in res["honored"]:
        res["notes"].append("[release] and [no release] both present — the release flow defers to the maintainer")
    if "[compat skip]" in res["honored"] and "[compat full]" in res["honored"]:
        res["notes"].append("[compat skip] and [compat full] both present — last one wins")
    if res["changelog_skip"] and res["changelog_text"]:
        res["notes"].append("[changelog skip] and [changelog: <text>] both present — skip wins downstream")

    return res


def _match_tag(inner: str):
    probe = inner.lower()
    tag = _SIMPLE_LOOKUP.get(probe)
    if tag is not None:
        return tag, None
    for regex, ptag in _PATTERN_LOOKUP:
        m = regex.fullmatch(inner)
        if m:
            return ptag, m
    return None, None


# --- CLI -------------------------------------------------------------------

def _check_docs_sync(doc: Path) -> list[str]:
    """Every registered syntax and alias must appear verbatim in the doc."""
    if not doc.exists():
        return [f"{doc} does not exist"]
    text = doc.read_text(encoding="utf-8")
    missing = []
    for tag in REGISTRY:
        if f"`{tag.display}`" not in text:
            missing.append(tag.display)
        for alias in tag_aliases(tag):
            if f"`[{alias}]`" not in text:
                missing.append(f"[{alias}] (alias of {tag.display})")
    return missing


def _cmd_check(args) -> int:
    res = parse(args.check, ref=args.ref, fork=args.fork)
    if args.json:
        print(json.dumps(res, ensure_ascii=False, indent=2))
        return 0
    honored = ", ".join(i.strip("[]") for i in res["honored"]) or "-"
    ignored = ", ".join(i.strip("[]") for i in res["ignored"]) or "-"
    print(f"tags: honored=[{honored}] ignored=[{ignored}] warnings={len(res['warnings'])}")
    for line in res["notes"] + res["warnings"]:
        print(f"  - {line}", file=sys.stderr)
    return 0


def _cmd_list() -> int:
    print("| Tag | Aliases | Tier | Works on | Effect |")
    print("|---|---|---|---|---|")
    for tag in REGISTRY:
        aliases = ", ".join(f"`[{a}]`" for a in tag_aliases(tag)) or "-"
        print(f"| `{tag.display}` | {aliases} | {tag.tier} | {tag.where} | {tag.effect} |")
    return 0


def _cmd_check_docs(args) -> int:
    missing = _check_docs_sync(Path(args.check_docs))
    if missing:
        print("docs out of sync with the registry in tools/ci/tags.py:", file=sys.stderr)
        for item in missing:
            print(f"  - {item}", file=sys.stderr)
        return 1
    print("docs/ci-tags.md is in sync with the registry")
    return 0


def main(argv: list[str] | None = None) -> int:
    # windows consoles default to cp1251 and choke on unicode output
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8")
        except Exception:
            pass
    ap = argparse.ArgumentParser(description="Continuum CI tag parser")
    group = ap.add_mutually_exclusive_group(required=True)
    group.add_argument("--check", metavar="STRING", help="parse STRING, print a summary line")
    group.add_argument("--list", action="store_true", help="print the registry as a markdown table")
    group.add_argument("--check-docs", metavar="PATH", help="verify PATH lists every registered tag and alias")
    ap.add_argument("--ref", default=None, help="branch the pipeline runs on (e.g. master)")
    ap.add_argument("--fork", action="store_true", help="untrusted fork-PR context (tier A only)")
    ap.add_argument("--json", action="store_true", help="with --check: dump the full result as JSON")
    args = ap.parse_args(argv)

    if args.check is not None:
        return _cmd_check(args)
    if args.list:
        return _cmd_list()
    return _cmd_check_docs(args)


if __name__ == "__main__":
    sys.exit(main())
