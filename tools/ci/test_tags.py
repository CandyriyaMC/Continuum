"""Tests for tools/ci/tags.py — the CI tag parser.

Covers: every registered tag, aliases, master/fork tier filtering,
did-you-mean, kwarg validation, and injection safety.
"""

import json
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import tags  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[2]
DOC_PATH = REPO_ROOT / "docs" / "ci-tags.md"
TAGS_PY = Path(__file__).resolve().parent / "tags.py"


def honored_outputs(res):
    """Result dict without the human-report fields, for comparison."""
    return {k: v for k, v in res.items() if k not in ("honored", "ignored", "notes", "warnings")}


def parse_one(source, **kwargs):
    res = tags.parse(source, **kwargs)
    assert res["warnings"] == [], f"unexpected warnings: {res['warnings']}"
    return res


# --- the registry itself ----------------------------------------------------

def test_registry_size_and_uniqueness():
    assert len(tags.REGISTRY) == 30
    inners = [t.inner for t in tags.REGISTRY]
    assert len(set(inners)) == len(inners)


def test_registry_tiers_are_known():
    for tag in tags.REGISTRY:
        assert tag.tier in (tags.TIER_A, tags.TIER_B, tags.TIER_EMERGENCY)


# --- every tag, one fixture-ish pass each -----------------------------------

CASES = [
    # (input, expected output subset)
    ("[ci skip]", {"ci_skip": True}),
    ("[docs only]", {"docs_only": True}),
    ("[build none]", {"build": "none"}),
    ("[hotfix]", {"hotfix": True}),
    ("[paper only]", {"build": "paper"}),
    ("[velocity only]", {"build": "velocity"}),
    ("[common only]", {"build": "common"}),
    ("[no folia]", {"folia": False}),
    ("[build full]", {"build_full": True, "build": "full"}),
    ("[compat skip]", {"compat": "skip"}),
    ("[compat full]", {"compat": "full"}),
    ("[compat mc=26.3,1.21.11 only]", {"compat": "only", "compat_mc": ["26.3", "1.21.11"]}),
    ("[compat ignore mc=1.21.11 reason=broken-flatblock]",
     {"compat_ignore": [{"mc": "1.21.11", "reason": "broken-flatblock"}]}),
    ("[compat java=25]", {"compat_java": ["25"]}),
    ("[compat java=21,25]", {"compat_java": ["21", "25"]}),
    ("[compat nightly]", {"compat": "nightly"}),
    ("[discord skip]", {"discord": "skip"}),
    ("[discord release]", {"discord": "release"}),
    ("[no ping]", {"no_ping": True}),
    ("[changelog skip]", {"changelog_skip": True}),
    ("[changelog: add vanish command]", {"changelog_text": "add vanish command"}),
    ("[release notes]", {"release_notes": True}),
    ("[release]", {"release_candidate": True}),
    ("[no release]", {"no_release": True}),
    ("[changelog breaking]", {"changelog_breaking": True}),
    ("[commits count-reset]", {"commits_count_reset": True}),
    ("[tests skip]", {"tests": "skip"}),
    ("[lint skip]", {"lint": "skip"}),
    ("[flaky retry=3]", {"flaky_retry": 3}),
    ("[ci debug]", {"ci_debug": True}),
    ("[tags audit]", {"tags_audit": True}),
]


@pytest.mark.parametrize("source,expected", CASES, ids=[c[0] for c in CASES])
def test_every_registered_tag_is_honored(source, expected):
    res = parse_one(source, ref="dev")
    for key, value in expected.items():
        assert res[key] == value
    assert res["honored"], f"{source} did not land in honored"


@pytest.mark.parametrize("source,expected", CASES, ids=[c[0] for c in CASES])
def test_each_registered_tag_appears_in_the_result_exactly_once(source, expected):
    res = parse_one(source, ref="dev")
    assert len(res["honored"]) == 1


# --- aliases -----------------------------------------------------------------

ALIAS_CASES = [
    ("[skip ci]", "[ci skip]"),
    ("[no ci]", "[ci skip]"),
    ("[only docs]", "[docs only]"),
    ("[skip compat]", "[compat skip]"),
    ("[skip discord]", "[discord skip]"),
    ("[skip changelog]", "[changelog skip]"),
    ("[skip tests]", "[tests skip]"),
    ("[skip lint]", "[lint skip]"),
]


@pytest.mark.parametrize("alias,canonical", ALIAS_CASES)
def test_alias_produces_same_outputs_as_canonical(alias, canonical):
    res_alias = parse_one(alias, ref="dev")
    res_canonical = parse_one(canonical, ref="dev")
    assert honored_outputs(res_alias) == honored_outputs(res_canonical)
    # the gate always logs the canonical display, not the alias the user typed
    assert res_alias["honored"] == res_canonical["honored"]


def test_skip_prefix_rule_is_generic():
    # every "X skip" tag gets the muscle-memory "skip X" alias for free
    skip_tags = [t for t in tags.REGISTRY if t.inner.endswith(" skip")]
    assert len(skip_tags) >= 5
    for tag in skip_tags:
        assert f"skip {tag.inner[:-5]}" in tags.tag_aliases(tag)


# --- case normalization --------------------------------------------------------

@pytest.mark.parametrize("source", ["[COMPAT SKIP]", "[CoMpAt SkIp]", "[compat   skip]", "[compat\tskip]"])
def test_all_caps_and_whitespace_normalize(source):
    res = parse_one(source, ref="dev")
    assert res["compat"] == "skip"


def test_tag_in_the_epic_example_parses():
    res = parse_one("feat(paper): парсится [compat skip] [discord skip]", ref="dev")
    assert res["compat"] == "skip"
    assert res["discord"] == "skip"
    assert res["honored"] == ["[compat skip]", "[discord skip]"]


# --- master rules --------------------------------------------------------------

MASTER_IGNORED = [
    "[paper only]",
    "[velocity only]",
    "[common only]",
    "[no folia]",
    "[tests skip]",
    "[discord release]",
    "[compat ignore mc=1.21.11 reason=broken-flatblock]",
]


@pytest.mark.parametrize("source", MASTER_IGNORED)
def test_tier_b_artifact_tags_are_ignored_on_master(source):
    res = tags.parse(source, ref="master")
    assert res["honored"] == []
    assert len(res["ignored"]) == 1
    assert any("ignored on master" in n for n in res["notes"])


@pytest.mark.parametrize("ref", ["master", "refs/heads/master", "MASTER"])
def test_master_detection_variants(ref):
    assert tags.classify_ref(ref) == "master"


@pytest.mark.parametrize("ref", [None, "dev", "feature/x", "release/1.4.0", "refs/heads/dev"])
def test_non_master_refs_keep_tier_b(ref):
    res = parse_one("[paper only]", ref=ref)
    assert res["build"] == "paper"


@pytest.mark.parametrize("source", ["[hotfix]", "[release]", "[no release]"])
def test_master_scoped_tier_b_tags_are_still_honored_on_master(source):
    # these three are tier B by the registry, but their whole purpose is the
    # master-side flow (the epic annotates them as 'all' / 'PR to master' / 'master')
    res = parse_one(source, ref="master")
    assert len(res["honored"]) == 1


@pytest.mark.parametrize("source,key,value", [
    ("[docs only]", "docs_only", True),
    ("[build full]", "build_full", True),
    ("[compat skip]", "compat", "skip"),
    ("[compat full]", "compat", "full"),
    ("[changelog skip]", "changelog_skip", True),
    ("[lint skip]", "lint", "skip"),
    ("[flaky retry=2]", "flaky_retry", 2),
    ("[ci skip]", "ci_skip", True),
])
def test_tier_a_and_emergency_work_on_master(source, key, value):
    res = parse_one(source, ref="master")
    assert res[key] == value


def test_ignored_b_tag_does_not_weaken_the_build():
    res = tags.parse("[paper only]", ref="master")
    assert res["build"] == "full"


# --- fork (untrusted) rules ----------------------------------------------------

def test_fork_drops_tier_b_silently():
    res = tags.parse("[paper only]", fork=True)
    assert res["build"] == "full"
    assert res["honored"] == []
    assert res["warnings"] == []  # silent by design


def test_fork_drops_emergency():
    res = tags.parse("[ci skip]", fork=True)
    assert res["ci_skip"] is False


def test_fork_keeps_tier_a():
    res = parse_one("[compat skip]", fork=True)
    assert res["compat"] == "skip"


# --- unknown tags / did-you-mean -----------------------------------------------

def test_unknown_tag_warns_and_is_inert():
    res = tags.parse("[kompat skip]", ref="dev")
    assert res["honored"] == []
    assert len(res["warnings"]) == 1
    assert "[kompat skip]" in res["warnings"][0]


DYM_CASES = [
    ("[compat ski]", "[compat skip]"),
    ("[dicord skip]", "[discord skip]"),
    ("[changlog skip]", "[changelog skip]"),
    ("[docs onyl]", "[docs only]"),
    ("[lint ski]", "[lint skip]"),
    ("[test skip]", "[tests skip]"),
    ("[no pingg]", "[no ping]"),
    ("[build ful]", "[build full]"),
]


@pytest.mark.parametrize("source,suggestion", DYM_CASES)
def test_did_you_mean_suggests_the_closest_tag(source, suggestion):
    res = tags.parse(source, ref="dev")
    assert len(res["warnings"]) == 1
    assert suggestion in res["warnings"][0]
    assert res["honored"] == []


def test_did_you_mean_stays_quiet_for_garbage():
    res = tags.parse("[xqzk]", ref="dev")
    assert len(res["warnings"]) == 1
    assert "did you mean" not in res["warnings"][0]


def test_cyrillic_unknown_tag_does_not_crash_and_gets_no_wild_suggestion():
    res = tags.parse("[компат скип]", ref="dev")
    assert len(res["warnings"]) == 1
    assert "did you mean" not in res["warnings"][0]


def test_empty_brackets_warn():
    res = tags.parse("[]", ref="dev")
    assert len(res["warnings"]) == 1
    assert "empty tag" in res["warnings"][0]


def test_oversized_bracket_warns_instead_of_vanishing():
    res = tags.parse("[" + "a" * 201 + "]", ref="dev")
    assert any("too long" in w for w in res["warnings"])


# --- kwargs parsing & validation -------------------------------------------------

def test_compat_mc_single_version():
    res = parse_one("[compat mc=1.21.11 only]", ref="dev")
    assert res["compat_mc"] == ["1.21.11"]


def test_compat_mc_snapshot_style_versions():
    res = parse_one("[compat mc=26.3-pre2,1.21.11 only]", ref="dev")
    assert res["compat_mc"] == ["26.3-pre2", "1.21.11"]


@pytest.mark.parametrize("source", [
    "[compat mc=abc only]",            # version must start with a digit
    "[compat mc=26.3, only]",          # trailing comma -> empty token
    "[compat mc=26.3; rm -rf / only]", # shell junk does not parse
    "[compat mc=]",                    # empty value
])
def test_compat_mc_rejects_bad_values(source):
    res = tags.parse(source, ref="dev")
    assert res["compat"] == "default"
    assert len(res["warnings"]) == 1


def test_compat_java_list():
    res = parse_one("[compat java=21,25]", ref="dev")
    assert res["compat_java"] == ["21", "25"]


def test_compat_ignore_happy_path():
    res = parse_one("[compat ignore mc=1.21.11 reason=broken-flatblock]", ref="dev")
    assert res["compat_ignore"] == [{"mc": "1.21.11", "reason": "broken-flatblock"}]


def test_compat_ignore_reason_slug_allows_dash_dot_underscore():
    res = parse_one("[compat ignore mc=1.21.11 reason=really_broken.thing-v2]", ref="dev")
    assert res["compat_ignore"][0]["reason"] == "really_broken.thing-v2"


@pytest.mark.parametrize("source,fragment", [
    ("[compat ignore mc=1.21.11]", "reason"),                    # missing reason
    ("[compat ignore reason=broken-flatblock]", "mc"),            # missing mc
    ("[compat ignore mc=1.21.11 reason=]", "reason"),             # empty reason
    ("[compat ignore mc=1.21.11 reason=$(whoami)]", "reason"),    # shell junk as reason
    ("[compat ignore mc=1.21.11 reason=broken flatblock]", "key=value"),  # reason with a space
    ("[compat ignore mc=1.21.11 bogus=thing reason=x]", "unknown key"),   # stray kwarg
    ("[compat ignore mc=1.21.11 reason=a reason=b]", "duplicate key"),    # duplicated kwarg
    ("[compat ignore mc=1.21.11; rm -rf / reason=broken]", "mc"),         # mc injection
])
def test_compat_ignore_rejects_invalid_input(source, fragment):
    res = tags.parse(source, ref="dev")
    assert res["compat_ignore"] == []
    assert len(res["warnings"]) == 1
    assert fragment in res["warnings"][0]


def test_compat_ignore_requires_both_kwargs_together():
    res = tags.parse("[compat ignore]", ref="dev")
    assert res["compat_ignore"] == []
    assert len(res["warnings"]) == 1


@pytest.mark.parametrize("source,expected", [
    ("[flaky retry=1]", 1),
    ("[flaky retry=10]", 10),
])
def test_flaky_retry_bounds_accepted(source, expected):
    assert parse_one(source, ref="dev")["flaky_retry"] == expected


@pytest.mark.parametrize("source", ["[flaky retry=0]", "[flaky retry=99]", "[flaky retry=x]"])
def test_flaky_retry_out_of_range_or_junk_is_not_honored(source):
    res = tags.parse(source, ref="dev")
    assert res["flaky_retry"] == 0
    assert len(res["warnings"]) == 1


def test_changelog_text_with_spaces():
    res = parse_one("[changelog: stop the paper jar from vanishing]", ref="dev")
    assert res["changelog_text"] == "stop the paper jar from vanishing"


def test_changelog_text_over_140_chars_is_rejected():
    res = tags.parse("[changelog: " + "x" * 141 + "]", ref="dev")
    assert res["changelog_text"] is None
    assert len(res["warnings"]) == 1


def test_changelog_text_strips_control_chars():
    res = parse_one("[changelog: it\x07 broke]", ref="dev")
    assert res["changelog_text"] == "it broke"


# --- injection safety -----------------------------------------------------------

def test_shell_substitution_inside_brackets_is_inert():
    res = parse_one("[changelog: $(rm -rf /)]", ref="dev")
    assert res["changelog_text"] == "$(rm -rf /)"


def test_backticks_inside_brackets_are_inert():
    res = parse_one("[changelog: `id`]", ref="dev")
    assert res["changelog_text"] == "`id`"


def test_sql_fragment_inside_brackets_is_inert():
    res = parse_one("[changelog: '; DROP TABLE users; --]", ref="dev")
    assert res["changelog_text"] == "'; DROP TABLE users; --"


def test_bracket_with_command_is_unknown_not_executed():
    res = tags.parse("[$(touch /tmp/pwned)]", ref="dev")
    assert res["honored"] == []
    assert len(res["warnings"]) == 1


def test_text_after_a_tag_is_not_parsed():
    res = tags.parse("[compat skip]; rm -rf /", ref="dev")
    assert res["compat"] == "skip"
    assert res["warnings"] == []


def test_newlines_inside_brackets_are_collapsed_to_one_tag():
    res = tags.parse("[compat\nskip]", ref="dev")
    assert res["compat"] == "skip"
    assert res["honored"] == ["[compat skip]"]


def test_deeply_nested_brackets_do_not_recurse():
    res = tags.parse("[[[[compat skip]]]]", ref="dev")
    assert res["honored"].count("[compat skip]") >= 1  # inner bracket matched, outer junk warns


# --- conflicts & post-pass ------------------------------------------------------

@pytest.mark.parametrize("first,second", [
    ("[build full]", "[paper only]"),
    ("[paper only]", "[build full]"),
])
def test_build_full_overrides_build_scope_tags_in_any_order(first, second):
    res = tags.parse(f"{first} {second}", ref="dev")
    assert res["build"] == "full"
    assert any("overrides" in n for n in res["notes"])


def test_release_and_no_release_both_honored_with_a_note():
    res = tags.parse("[release] [no release]", ref="master")
    assert res["release_candidate"] and res["no_release"]
    assert any("[release] and [no release]" in n for n in res["notes"])


def test_compat_skip_and_full_conflict_note():
    res = tags.parse("[compat skip] [compat full]", ref="dev")
    assert res["compat"] == "full"  # last wins
    assert any("[compat skip] and [compat full]" in n for n in res["notes"])


def test_changelog_skip_and_text_conflict_note():
    res = tags.parse("[changelog skip] [changelog: hello]", ref="dev")
    assert res["changelog_skip"] and res["changelog_text"] == "hello"
    assert any("[changelog skip] and [changelog: <text>]" in n for n in res["notes"])


def test_plain_text_without_tags_keeps_defaults():
    res = tags.parse("fix the vanish command", ref="dev")
    assert res["build"] == "full"
    assert res["honored"] == []
    assert res["warnings"] == []


def test_none_source_is_tolerated():
    res = tags.parse(None)
    assert res["build"] == "full"


# --- CLI ------------------------------------------------------------------------

def run_cli(*args):
    return subprocess.run(
        [sys.executable, str(TAGS_PY), *args],
        capture_output=True, text=True, encoding="utf-8",
    )


def test_cli_check_prints_a_summary_line():
    proc = run_cli("--check", "feat: x [compat skip] [discord skip]")
    assert proc.returncode == 0
    out_lines = [l for l in proc.stdout.splitlines() if l.strip()]
    assert len(out_lines) == 1
    assert out_lines[0].startswith("tags: honored=[compat skip, discord skip]")
    assert "warnings=0" in out_lines[0]


def test_cli_check_with_unknown_tag_still_exits_zero():
    proc = run_cli("--check", "feat: x [kompat skip]")
    assert proc.returncode == 0
    assert "warnings=1" in proc.stdout


def test_cli_check_master_ref():
    proc = run_cli("--check", "feat: x [paper only]", "--ref", "master")
    assert proc.returncode == 0
    assert "ignored=[paper only]" in proc.stdout


def test_cli_json_output_is_machine_readable():
    proc = run_cli("--check", "feat: x [compat skip]", "--json")
    assert proc.returncode == 0
    data = json.loads(proc.stdout)
    assert data["compat"] == "skip"
    assert data["honored"] == ["[compat skip]"]


def test_cli_list_contains_the_whole_registry():
    proc = run_cli("--list")
    assert proc.returncode == 0
    for tag in tags.REGISTRY:
        assert f"`{tag.display}`" in proc.stdout
        for alias in tags.tag_aliases(tag):
            assert f"`[{alias}]`" in proc.stdout


def test_cli_check_docs_passes_on_the_real_doc():
    proc = run_cli("--check-docs", str(DOC_PATH))
    assert proc.returncode == 0


def test_cli_check_docs_fails_on_a_stub_doc(tmp_path):
    stub = tmp_path / "ci-tags.md"
    stub.write_text("# ci tags\n\nnothing here\n", encoding="utf-8")
    proc = run_cli("--check-docs", str(stub))
    assert proc.returncode == 1
    assert "out of sync" in proc.stderr


def test_check_docs_sync_helper_reports_missing_entries(tmp_path):
    stub = tmp_path / "ci-tags.md"
    stub.write_text("# ci tags\n\nonly `[ci skip]` here\n", encoding="utf-8")
    missing = tags._check_docs_sync(stub)
    assert any("[compat skip]" in m for m in missing)
    assert "[ci skip]" not in missing


# --- docs sync -------------------------------------------------------------------

def test_docs_list_every_registered_tag_and_alias():
    assert DOC_PATH.exists(), "docs/ci-tags.md is missing"
    text = DOC_PATH.read_text(encoding="utf-8")
    for tag in tags.REGISTRY:
        assert f"`{tag.display}`" in text, f"{tag.display} missing from docs/ci-tags.md"
        for alias in tags.tag_aliases(tag):
            assert f"`[{alias}]`" in text, f"alias [{alias}] missing from docs/ci-tags.md"
