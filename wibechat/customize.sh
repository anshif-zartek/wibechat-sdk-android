#!/usr/bin/env bash
# Runs inside the rebranded tree after the overlay is applied. Use it for
# WibeChat-specific edits that should survive every upstream sync.
set -euo pipefail

if ! grep -q '^org.gradle.tooling.parallel=' gradle.properties; then
  printf '\n# Enables parallel sync for Gradle 9.4+\norg.gradle.tooling.parallel=true\n' >> gradle.properties
fi

# Let the publish plugin look up the staging profile when STAGING_PROFILE_ID is
# empty, instead of sending an empty ID.
if grep -q '^ *stagingProfileId = STAGING_PROFILE_ID$' build.gradle; then
  perl -pi -e 's/^( *)stagingProfileId = STAGING_PROFILE_ID$/$1if (STAGING_PROFILE_ID) stagingProfileId = STAGING_PROFILE_ID/' build.gradle
else
  echo "customize.sh: stagingProfileId line not found in build.gradle; check the release setup." >&2
fi

# Replace the upstream logo banner in every sample app with the brand name as text.
python3 - <<'EOF'
import os
import re
import sys

BANNER = "sample-app-common/src/main/res/drawable/banner_dark.xml"

VIEWS_IMAGE = re.compile(r'<ImageView\b([^>]*?)\s*android:src="@drawable/banner_dark"\s*/>')
VIEWS_TEXT_ATTRS = [
    'android:text="@string/brand_name"',
    'android:textColor="?android:attr/textColorPrimary"',
    'android:textSize="32sp"',
    'android:textStyle="bold"',
]
COMPOSE_IMAGE = re.compile(
    r'(?P<i>[ \t]*)Image\(\n\s*painter = painterResource\(id = R\.drawable\.banner_dark\),\n'
    r'\s*contentDescription = [^\n]*\n(?P=i)\)'
)
COMPOSE_TEXT = (
    "{i}Text(\n"
    "{i}    text = stringResource(R.string.brand_name),\n"
    "{i}    fontSize = 32.sp,\n"
    "{i}    fontWeight = FontWeight.Bold,\n"
    "{i})"
)


def views_text(match):
    attrs = match.group(1).rstrip()
    indent = re.search(r"\n([ \t]+)android:", match.group(1)).group(1)
    return "<TextView" + attrs + "".join("\n" + indent + a for a in VIEWS_TEXT_ATTRS) + " />"


def import_key(line):
    name = line[len("import "):]
    last = name.startswith(("java.", "javax.", "kotlin.")) or " as " in name
    return (last, name)


def fix_imports(text):
    lines = text.split("\n")
    idx = [n for n, line in enumerate(lines) if line.startswith("import ")]
    imports = set(lines[n] for n in idx)
    body = "\n".join(line for line in lines if not line.startswith("import "))

    material = "material3" if any(".material3." in i for i in imports) else "material"
    wanted = [
        "import androidx.compose.ui.res.stringResource",
        "import androidx.compose.ui.text.font.FontWeight",
        "import androidx.compose.ui.unit.sp",
    ]
    if "import androidx.compose.%s.*" % material not in imports:
        wanted.append("import androidx.compose.%s.Text" % material)
    imports.update(wanted)
    if not re.search(r"\bImage\(", body):
        imports.discard("import androidx.compose.foundation.Image")
    if "painterResource" not in body:
        imports.discard("import androidx.compose.ui.res.painterResource")

    first, last = idx[0], idx[-1]
    block = sorted(imports, key=import_key)
    return "\n".join(lines[:first] + block + lines[last + 1:])


def iter_sources():
    for root, dirs, files in os.walk("."):
        dirs[:] = [d for d in dirs if d not in (".git", "build")
                   and not (root == "." and d == "wibechat")]
        for name in files:
            if name.endswith((".kt", ".java", ".xml")):
                yield os.path.join(root, name)


for path in iter_sources():
    with open(path, encoding="utf-8") as f:
        text = f.read()
    if "banner_dark" not in text or path.endswith("banner_dark.xml"):
        continue
    if path.endswith(".xml"):
        new_text = VIEWS_IMAGE.sub(views_text, text)
    else:
        new_text, count = COMPOSE_IMAGE.subn(lambda m: COMPOSE_TEXT.format(i=m.group("i")), text)
        if count:
            new_text = fix_imports(new_text)
    if new_text != text:
        with open(path, "w", encoding="utf-8") as f:
            f.write(new_text)

remaining = [p for p in iter_sources()
             if not p.endswith("banner_dark.xml") and "banner_dark" in open(p, encoding="utf-8").read()]
if remaining:
    print("customize.sh: logo banner still used in %s; keeping it." % ", ".join(remaining), file=sys.stderr)
elif os.path.exists(BANNER):
    os.remove(BANNER)
EOF
