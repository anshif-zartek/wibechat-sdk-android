#!/usr/bin/env bash
#
# Fast-forwards main to upstream, regenerates the rebranded branch from the
# newest upstream release (carrying this tooling along), verifies that it
# builds, and pushes both, tagging new versions for release.
# main is never committed to; it stays an exact mirror of upstream.
#
# Usage: ./sync.sh [--no-push] [--skip-build] [--no-tag] [--yes]
#   --no-push     Generate and build only. Pushes nothing, moves no local branch,
#                 and keeps the generated tree for inspection.
#   --skip-build  Skip the ./gradlew assembleDebug check of the generated tree.
#   --no-tag      Do not push a release tag for a new upstream version.
#   --yes         Push without asking for confirmation.

set -euo pipefail

PUSH=1
BUILD=1
TAG=1
ASSUME_YES=0
for arg in "$@"; do
  case "$arg" in
    --no-push) PUSH=0 ;;
    --no-tag) TAG=0 ;;
    --skip-build) BUILD=0 ;;
    --yes) ASSUME_YES=1 ;;
    -h|--help) sed -n '3,14p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

# Run from a temporary copy: moving the rebrand branch can rewrite this file mid-run.
if [[ -z "${WIBECHAT_SYNC_TOOLS:-}" ]]; then
  src_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  repo="$(git -C "$src_dir" rev-parse --show-toplevel)"
  tools_copy="$(mktemp -d "${TMPDIR:-/tmp}/wibechat-tools.XXXXXX")"
  cp -R "$src_dir/." "$tools_copy/"
  WIBECHAT_SYNC_TOOLS="$tools_copy" WIBECHAT_SYNC_REPO="$repo" exec bash "$tools_copy/sync.sh" "$@"
fi

TOOLS="$WIBECHAT_SYNC_TOOLS"
REPO="$WIBECHAT_SYNC_REPO"
TOOLS_DIR_NAME="wibechat"
# shellcheck source=brand.conf
source "$TOOLS/brand.conf"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/wibechat-sync.XXXXXX")"
KEEP_WORK=0

step() { printf '\n==> %s\n' "$*"; }
warn() { printf 'warning: %s\n' "$*" >&2; }
die() { printf 'error: %s\n' "$*" >&2; KEEP_WORK=1; exit 1; }
g() { git -C "$REPO" "$@"; }
is_ancestor() { g merge-base --is-ancestor "$1" "$2"; }

cleanup() {
  if [[ "$KEEP_WORK" == 1 ]]; then
    echo "Work directory kept for inspection: $WORK"
  else
    rm -rf "$WORK"
  fi
  rm -rf "$TOOLS"
}
trap cleanup EXIT
trap 'KEEP_WORK=1' ERR

command -v python3 >/dev/null || die "python3 is required."

# Prints the worktree path that has refs/heads/$1 checked out, if any.
worktree_of() {
  g worktree list --porcelain | awk -v ref="refs/heads/$1" '
    /^worktree / { path = substr($0, 10) }
    $0 == "branch " ref { print path; exit }'
}

# Moves local branch $1 to commit $2, updating its worktree if checked out.
move_local_branch() {
  local branch="$1" target="$2" wt
  wt="$(worktree_of "$branch")"
  if [[ -z "$wt" ]]; then
    g update-ref "refs/heads/$branch" "$target"
  elif git -C "$wt" merge-base --is-ancestor HEAD "$target"; then
    git -C "$wt" merge --ff-only --quiet "$target" \
      || warn "could not fast-forward $branch in $wt; run 'git merge --ff-only origin/$branch' there."
  else
    git -C "$wt" reset --keep --quiet "$target" \
      || warn "could not move $branch in $wt (local changes?); run 'git reset --keep origin/$branch' there."
  fi
}

# Clears what would block moving this checkout onto the generated commit: the
# local tooling (already copied into that commit) and the protocol submodule
# checkout (replaced by vendored files).
release_checkout_for_rebrand() {
  if [[ "$(worktree_of "$REBRAND_BRANCH")" != "$REPO" ]]; then
    return
  fi
  g checkout --quiet HEAD -- sync.sh "$TOOLS_DIR_NAME" 2>/dev/null || true
  g clean --quiet -fd -- sync.sh "$TOOLS_DIR_NAME"
  if [[ -e "$REPO/protocol/.git" ]]; then
    g submodule deinit --quiet --force protocol
    rm -rf "$REPO/protocol"
  fi
}

step "Fetching origin and upstream"
if ! g remote get-url upstream >/dev/null 2>&1; then
  g remote add upstream "$UPSTREAM_URL"
fi
# Upstream tags share names with ours (v2.29.1), so keep them in their own namespace.
g config remote.upstream.tagOpt --no-tags
g fetch --quiet origin
g fetch --quiet --no-tags upstream "$UPSTREAM_BRANCH" "+refs/tags/v*:refs/upstream-tags/v*"
upstream_head="$(g rev-parse "refs/remotes/upstream/$UPSTREAM_BRANCH")"

step "Fast-forwarding $MAIN_BRANCH to upstream/$UPSTREAM_BRANCH"
origin_main="$(g rev-parse -q --verify "refs/remotes/origin/$MAIN_BRANCH" || true)"
for ref in "refs/heads/$MAIN_BRANCH" "refs/remotes/origin/$MAIN_BRANCH"; do
  sha="$(g rev-parse -q --verify "$ref" || true)"
  if [[ -n "$sha" ]] && ! is_ancestor "$sha" "$upstream_head"; then
    die "$ref has commits that are not in upstream; $MAIN_BRANCH must mirror upstream exactly."
  fi
done
new_main="$upstream_head"
if [[ "$origin_main" == "$new_main" ]]; then
  echo "Already up to date with upstream."
else
  echo "Fast-forwarding to ${new_main:0:10}."
fi

if [[ "$SYNC_FROM" == "release" ]]; then
  upstream_release="$(g for-each-ref --format='%(refname:lstrip=2)' refs/upstream-tags/ \
    | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -1 || true)"
  [[ -n "$upstream_release" ]] || die "no upstream vX.Y.Z release tags found."
  source_sha="$(g rev-parse "refs/upstream-tags/$upstream_release^{commit}")"
  source_label="upstream release $upstream_release"
else
  source_sha="$new_main"
  source_label="upstream/$UPSTREAM_BRANCH"
fi

step "Exporting $source_label with vendored protocol files"
TREE="$WORK/tree"
mkdir -p "$TREE"
g archive "$source_sha" | tar -x -C "$TREE"

proto_sha="$(g ls-tree "$source_sha" protocol | awk '$2 == "commit" { print $3 }')"
[[ -n "$proto_sha" ]] || die "no protocol submodule found in $source_label."
proto_cache="$(cd "$REPO" && cd "$(git rev-parse --git-common-dir)" && pwd)/wibechat-protocol.git"
if [[ ! -d "$proto_cache" ]]; then
  git clone --quiet --bare "$PROTOCOL_URL" "$proto_cache"
fi
if ! git -C "$proto_cache" cat-file -e "$proto_sha^{commit}" 2>/dev/null; then
  git -C "$proto_cache" fetch --quiet "$PROTOCOL_URL" "$proto_sha"
fi
rm -rf "$TREE/protocol"
mkdir -p "$TREE/protocol"
git -C "$proto_cache" archive "$proto_sha" protobufs LICENSE | tar -x -C "$TREE/protocol"

step "Rebranding"
python3 "$TOOLS/rebrand.py" "$TREE" "$TOOLS"
# Carry this tooling into the generated branch, unmodified.
mkdir -p "$TREE/$TOOLS_DIR_NAME"
cp -R "$TOOLS/." "$TREE/$TOOLS_DIR_NAME/"
cat > "$TREE/sync.sh" <<'EOF'
#!/usr/bin/env bash
# Syncs upstream into main and regenerates this branch. See wibechat/README.md.
exec bash "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/wibechat/sync.sh" "$@"
EOF
chmod +x "$TREE/sync.sh"

step "Committing generated tree"
git_dir="$(g rev-parse --absolute-git-dir)"
export GIT_INDEX_FILE="$WORK/index"
git --git-dir="$git_dir" --work-tree="$TREE" -C "$TREE" add --all --force .
tree_sha="$(git --git-dir="$git_dir" write-tree)"
unset GIT_INDEX_FILE

version="$(awk -F= '$1 == "VERSION_NAME" { print $2 }' "$TREE/gradle.properties")"
prev_rebrand="$(g rev-parse -q --verify "refs/remotes/origin/$REBRAND_BRANCH" || true)"
orphan=0
if [[ -n "$prev_rebrand" ]] && g log -1 --format=%B "$prev_rebrand" | grep -q '^Synced-From: '; then
  if [[ "$(g rev-parse "$prev_rebrand^{tree}")" == "$tree_sha" ]]; then
    new_rebrand="$prev_rebrand"
    echo "Generated tree is unchanged since the last sync."
  else
    new_rebrand="$(g commit-tree "$tree_sha" -p "$prev_rebrand" \
      -m "Sync to v$version" -m "Synced-From: $source_sha")"
  fi
else
  orphan=1
  new_rebrand="$(g commit-tree "$tree_sha" \
    -m "Sync to v$version" -m "Synced-From: $source_sha")"
fi
echo "Generated commit: $new_rebrand"

# A new upstream version gets a release tag; pushing it publishes to Maven Central.
release_tag=""
if [[ "$TAG" == 1 && "$version" != *SNAPSHOT* ]] \
    && ! g ls-remote --exit-code --tags origin "refs/tags/v$version" >/dev/null; then
  release_tag="v$version"
  echo "Release tag to create: $release_tag"
fi

if [[ "$BUILD" == 1 ]]; then
  step "Building generated tree (./gradlew assembleDebug)"
  # Reuse the Android SDK location from any checkout of this repo.
  while IFS= read -r wt; do
    if [[ -f "$wt/local.properties" ]]; then
      cp "$wt/local.properties" "$TREE/local.properties"
      break
    fi
  done < <(g worktree list --porcelain | awk '/^worktree / { print substr($0, 10) }')
  (cd "$TREE" && ./gradlew --quiet assembleDebug) \
    || die "generated tree does not build. Fix the rebrand rules and rerun."
fi

if [[ "$PUSH" == 0 ]]; then
  KEEP_WORK=1
  step "Done (--no-push)"
  echo "Inspect the generated tree in $WORK/tree, or the commit with: git show --stat $new_rebrand"
  exit 0
fi

step "Ready to push"
echo "  $MAIN_BRANCH:        ${origin_main:0:10} -> ${new_main:0:10}"
echo "  $REBRAND_BRANCH: ${prev_rebrand:0:10} -> ${new_rebrand:0:10}"
if [[ -n "$release_tag" ]]; then
  echo "  tag $release_tag on ${new_rebrand:0:10} (starts the Maven Central release)"
fi
if [[ "$orphan" == 1 && -n "$prev_rebrand" ]]; then
  echo
  echo "  First generated sync: origin/$REBRAND_BRANCH will be REPLACED by a new"
  echo "  history with no upstream commits. Its current commits will be dropped."
fi
if [[ "$ASSUME_YES" != 1 ]]; then
  read -r -p "Push? [y/N] " reply
  [[ "$reply" == [yY]* ]] || { echo "Nothing pushed."; exit 0; }
fi

if [[ "$origin_main" != "$new_main" ]]; then
  g push --quiet origin "$new_main:refs/heads/$MAIN_BRANCH"
fi
if [[ "$orphan" == 1 ]]; then
  g push --quiet --force-with-lease="$REBRAND_BRANCH:$prev_rebrand" \
    origin "$new_rebrand:refs/heads/$REBRAND_BRANCH"
else
  g push --quiet origin "$new_rebrand:refs/heads/$REBRAND_BRANCH"
fi
if [[ -n "$release_tag" ]]; then
  g push --quiet origin "$new_rebrand:refs/tags/$release_tag"
fi
g fetch --quiet origin

move_local_branch "$MAIN_BRANCH" "$new_main"
if g rev-parse -q --verify "refs/heads/$REBRAND_BRANCH" >/dev/null; then
  release_checkout_for_rebrand
  move_local_branch "$REBRAND_BRANCH" "$new_rebrand"
fi

step "Synced $MAIN_BRANCH and $REBRAND_BRANCH (v$version)"
if [[ -n "$prev_rebrand" && "$new_rebrand" != "$prev_rebrand" && -n "$(worktree_of "$REBRAND_BRANCH")" ]]; then
  echo "Files changed under your checkout: in Android Studio, run"
  echo "File > Sync Project with Gradle Files before building or running."
fi
