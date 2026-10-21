#!/usr/bin/env python3
"""Rebrands an exported upstream source tree in place.

Usage: rebrand.py <tree_dir> <tooling_dir>

Steps, in order:
  1. Delete upstream-only files (REMOVE_PATHS).
  2. Rewrite file contents and rename paths, leaving PROTECTED text untouched.
  3. Copy <tooling_dir>/overlay over the tree.
  4. Append the WibeChat section to NOTICE.
  5. Run <tooling_dir>/customize.sh inside the tree, if present.
  6. Report any remaining upstream brand mentions.
"""
import os
import re
import shutil
import subprocess
import sys

# Upstream release tooling and assets that have no meaning in the rebranded repo.
REMOVE_PATHS = [
    ".changeset",
    ".github",
    ".gitmodules",
    "CHANGELOG.md",
    "aqua.yaml",
    "ci",
    "package.json",
    "pnpm-lock.yaml",
    "release-instructions.md",
    "sample-app-common/src/main/res/drawable/ic_logo_dark.xml",
    "sync.sh",
    "wibechat",
]

# Apache 2.0 requires LICENSE and NOTICE to be redistributed unmodified.
VERBATIM_FILES = {"LICENSE", "NOTICE"}

# Text that must survive the rebrand byte-for-byte.
PROTECTED = re.compile("|".join([
    # Copyright notices required by the Apache 2.0 license.
    r"LiveKit, Inc\.",
    # Packages and JNI symbols compiled into the prebuilt libwebrtc binary.
    r"livekit(?=[./_]org[./_](?:webrtc|jni_zero))",
    # Maven coordinates, packages and native library of the prebuilt uniffi core.
    r"io\.livekit:livekit-uniffi-android",
    r"io\.livekit\.uniffi",
    r"livekit-uniffi-android",
    r"livekit_uniffi",
    r"(?<=uniffi[./])livekit(?=_)",
    # LiveKit Cloud hosts and services the SDK talks to.
    r"cloud-api\.livekit\.io",
    r"\.livekit\.(?:cloud|run)\b",
]))

PLACEHOLDER = re.compile("(\\d+)")
# The io.livekit package / Maven group root, dotted or as a path.
PACKAGE_ROOT = re.compile(r"(?<![\w-])io([./])livekit(?![\w-])")
# "LK" class prefixes (LKLog) and "lk" property prefixes (lkObjects). Wire keys such as
# "lk.agent.state" and the lkjingle native library are not followed by a capital letter.
LK_PREFIX = re.compile(r"(?<![A-Z])LK(?=[A-Z][a-z])")
LK_LOWER_PREFIX = re.compile(r"(?<![A-Za-z])lk(?=[A-Z])")
URL_TAIL = r"[^\s\"'<>()\[\]`]*"
LEFTOVER = re.compile(r"(?i:live[\s_-]?kit)|(?<![A-Z])LK(?=[A-Z][a-z])|(?<![A-Za-z])lk(?=[A-Z])")


def load_conf(path):
    conf = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            conf[key.strip()] = value.strip().strip('"')
    return conf


def strip_scheme(url):
    return re.sub(r"^https?://", "", url).rstrip("/")


class Rebrander:
    def __init__(self, conf):
        self.conf = conf
        self.url_rules = [
            (re.compile(r"github\.com/livekit/client-sdk-android"), strip_scheme(conf["REPO_URL"])),
            (re.compile(r"github\.com/livekit(?=/)"), strip_scheme(conf["GITHUB_ORG_URL"])),
            (re.compile(r"https?://docs\.livekit\.io" + URL_TAIL), conf["DOCS_URL"]),
            (re.compile(r"https?://(?:www\.)?livekit\.io" + URL_TAIL), conf["WEBSITE_URL"]),
        ]

    def brand_words(self, text):
        c = self.conf
        text = PACKAGE_ROOT.sub(lambda m: c["PACKAGE_ROOT"].replace(".", m.group(1)), text)
        text = text.replace("LIVEKIT", c["BRAND_UPPER"])
        text = text.replace("LiveKit", c["BRAND_PASCAL"])
        text = text.replace("Livekit", c["BRAND_CAPITALIZED"])
        text = text.replace("liveKit", c["BRAND_CAMEL"])
        text = text.replace("livekit", c["BRAND_LOWER"])
        text = LK_PREFIX.sub(c["BRAND_PREFIX"], text)
        return LK_LOWER_PREFIX.sub(c["BRAND_PREFIX"].lower(), text)

    def transform(self, text, rewrite_urls=True):
        saved = []

        def stash(match):
            saved.append(match.group(0))
            return "%d" % (len(saved) - 1)

        text = PROTECTED.sub(stash, text)
        if rewrite_urls:
            for pattern, replacement in self.url_rules:
                text = pattern.sub(lambda _m, r=replacement: r, text)
        text = self.brand_words(text)
        return PLACEHOLDER.sub(lambda m: saved[int(m.group(1))], text)


def read_text(path):
    """Returns the file's text, or None for binary or non-UTF-8 files."""
    with open(path, "rb") as f:
        data = f.read()
    if b"\0" in data[:8192]:
        return None
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return None


def iter_files(tree):
    for root, dirs, files in os.walk(tree):
        dirs[:] = [d for d in dirs if d != ".git"]
        for name in files:
            path = os.path.join(root, name)
            if not os.path.islink(path):
                yield path


def remove_paths(tree):
    for rel in REMOVE_PATHS:
        path = os.path.join(tree, rel)
        if os.path.isdir(path) and not os.path.islink(path):
            shutil.rmtree(path)
        elif os.path.lexists(path):
            os.remove(path)


def rewrite_contents(tree, rebrander):
    changed = 0
    for path in iter_files(tree):
        if os.path.basename(path) in VERBATIM_FILES:
            continue
        text = read_text(path)
        if text is None:
            continue
        new_text = rebrander.transform(text)
        if new_text != text:
            with open(path, "wb") as f:
                f.write(new_text.encode("utf-8"))
            changed += 1
    return changed


def rename_paths(tree, rebrander):
    """Renames files by whole relative path, so PROTECTED paths such as
    livekit/org/webrtc are matched in full, then prunes emptied directories."""
    renamed = 0
    for path in list(iter_files(tree)) + [p for p in walk_links(tree)]:
        rel = os.path.relpath(path, tree)
        new_rel = rebrander.transform(rel, rewrite_urls=False)
        if new_rel != rel:
            dst = os.path.join(tree, new_rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            os.replace(path, dst)
            renamed += 1
    for root, dirs, files in os.walk(tree, topdown=False):
        if root != tree and not os.listdir(root):
            os.rmdir(root)
    return renamed


def walk_links(tree):
    for root, dirs, files in os.walk(tree):
        for name in files + dirs:
            path = os.path.join(root, name)
            if os.path.islink(path):
                yield path


def apply_overlay(tree, tooling):
    overlay = os.path.join(tooling, "overlay")
    if os.path.isdir(overlay):
        shutil.copytree(overlay, tree, dirs_exist_ok=True)


def append_notice(tree, conf):
    path = os.path.join(tree, "NOTICE")
    if not os.path.exists(path):
        return
    section = (
        "\n\n{name} Android SDK\n"
        "This product is a modified version of the LiveKit Android SDK\n"
        "(https://github.com/livekit/client-sdk-android), used under the\n"
        "Apache License, Version 2.0. Modifications Copyright {name}.\n"
    ).format(name=conf["BRAND_PASCAL"])
    with open(path, "a", encoding="utf-8") as f:
        f.write(section)


def run_customize_hook(tree, tooling):
    hook = os.path.join(tooling, "customize.sh")
    if os.path.exists(hook):
        subprocess.run(["bash", hook], cwd=tree, check=True)


def report_leftovers(tree):
    hits = {}
    for path in iter_files(tree):
        rel = os.path.relpath(path, tree)
        if os.path.basename(path) in VERBATIM_FILES:
            continue
        count = len(LEFTOVER.findall(PROTECTED.sub("", rel)))
        text = read_text(path)
        if text is not None:
            count += len(LEFTOVER.findall(PROTECTED.sub("", text)))
        if count:
            hits[rel] = count
    if not hits:
        print("rebrand: no upstream brand mentions left outside protected text.")
        return
    print("rebrand: %d upstream brand mention(s) left in %d file(s):" % (sum(hits.values()), len(hits)))
    for rel, count in sorted(hits.items(), key=lambda kv: -kv[1])[:30]:
        print("  %4d  %s" % (count, rel))


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    tree, tooling = sys.argv[1], sys.argv[2]
    conf = load_conf(os.path.join(tooling, "brand.conf"))
    rebrander = Rebrander(conf)

    remove_paths(tree)
    changed = rewrite_contents(tree, rebrander)
    renamed = rename_paths(tree, rebrander)
    apply_overlay(tree, tooling)
    append_notice(tree, conf)
    run_customize_hook(tree, tooling)
    print("rebrand: rewrote %d file(s), renamed %d path(s)." % (changed, renamed))
    report_leftovers(tree)


if __name__ == "__main__":
    main()
