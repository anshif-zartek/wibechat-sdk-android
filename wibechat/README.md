# WibeChat rebrand tooling

This folder turns the upstream SDK into the WibeChat SDK. It lives on the
`wibechat-rebrand` branch and is carried into every generated commit unchanged.

## Branches

| Branch             | Contents                                         | Edit by hand?                         |
|--------------------|--------------------------------------------------|---------------------------------------|
| `main`             | Exact mirror of upstream. Never committed to.    | No                                    |
| `wibechat-rebrand` | Generated, rebranded SDK plus this tooling.      | Only `wibechat/`; the rest is regenerated |

## Syncing

Run from a checkout of `wibechat-rebrand`:

```sh
./sync.sh               # update main, regenerate, build, confirm, push both branches
./sync.sh --no-push     # dry run: generate and build only, keep the tree for inspection
./sync.sh --skip-build  # skip the ./gradlew assembleDebug check
./sync.sh --no-tag      # push without tagging a new version (no release)
./sync.sh --yes         # push without the confirmation prompt
```

The sync does the following:

1. Adds the `upstream` remote if missing, then fetches `origin` and `upstream`.
   Upstream tags are stored under `refs/upstream-tags/` so they never mix with ours.
2. Fast-forwards `main` to `upstream/main`. It stops if `main` has any commit
   that is not in upstream.
3. Exports the newest upstream `vX.Y.Z` release (or `upstream/main` when
   `SYNC_FROM="branch"` in `brand.conf`) and vendors the `.proto` files from the
   `protocol` submodule into `protocol/protobufs`. The generated branch has no submodule.
4. Runs `rebrand.py`, which removes upstream release tooling, rewrites names and
   paths, applies `overlay/`, appends to `NOTICE`, then runs `customize.sh`.
5. Copies this `wibechat/` folder (including uncommitted edits) and `sync.sh` in as-is.
6. Commits the tree on top of the previous generated commit, with a
   `Synced-From: <main sha>` trailer.
7. Builds it with `./gradlew assembleDebug`.
8. Asks for confirmation, then pushes `main` (if it moved) and `wibechat-rebrand`.
   If the upstream version has no `vX.Y.Z` tag in origin yet, it also pushes one,
   which starts a release.

The first sync replaces the history of `origin/wibechat-rebrand` with a new
history that has no upstream commits (force-with-lease push). Later syncs are
normal fast-forward pushes.

## Releasing to Maven Central

Pushing a `v*` tag runs `.github/workflows/release.yml` (it lives in
`overlay/.github/workflows/`). The workflow builds, runs the unit tests, then signs
and publishes `com.wibechat:wibechat-android`, `-camerax`, `-track-processors` and
`-test`, and creates a GitHub release.

- **New upstream version:** `./sync.sh` tags it (`v2.30.0`) for you.
- **WibeChat-only change on the same upstream version:** after syncing, tag the
  generated commit yourself with a suffix:

  ```sh
  git fetch origin
  git tag v2.29.1-wc.1 origin/wibechat-rebrand
  git push origin v2.29.1-wc.1
  ```

  The tag must start with `v` + the `VERSION_NAME` in `gradle.properties`.

One-time setup:

1. Create an account at https://central.sonatype.com and add the namespace
   `com.wibechat`. Verify it with the DNS TXT record it gives you on `wibechat.com`.
2. Generate a user token there (View Account → Generate User Token).
3. Create a GPG key and publish it to a keyserver:
   `gpg --full-generate-key`, then `gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>`.
4. Add these repository secrets in GitHub (Settings → Secrets and variables → Actions):

   | Secret                 | Value                                                          |
   |------------------------|----------------------------------------------------------------|
   | `CENTRAL_USERNAME`     | User token username                                            |
   | `CENTRAL_PASSWORD`     | User token password                                            |
   | `SIGNING_KEY_ID`       | Last 8 characters of the GPG key ID                            |
   | `SIGNING_KEY_PASSWORD` | GPG key passphrase                                             |
   | `SIGNING_KEY_ARMOR`    | `gpg --export-secret-keys --armor <KEY_ID> \| base64` (one line) |
   | `STAGING_PROFILE_ID`   | Optional; looked up automatically when empty                   |

## Adding WibeChat-specific changes

Edits outside `wibechat/` are overwritten by the next sync. Put them here instead:

- **Replace a whole file:** put it in `overlay/` at the same path, using the
  rebranded name, for example `overlay/sample-app-common/src/main/res/drawable/ic_logo.xml`.
- **Small edits to upstream files:** script them in `customize.sh`. It runs inside
  the generated tree after the overlay is applied.
- **Naming and links:** edit `brand.conf`.

Then run `./sync.sh`. The changes appear in the generated commit.

## What is deliberately not rebranded

| Kept as-is                                 | Why                                                                 |
|--------------------------------------------|---------------------------------------------------------------------|
| `LiveKit, Inc.` copyright headers, `LICENSE`, `NOTICE` | Required by the Apache 2.0 license.                    |
| `livekit.org.webrtc.*`, `lkjingle_peerconnection_so`   | Compiled into the prebuilt libwebrtc binary (JNI symbols). Needs a custom libwebrtc build to change. |
| `io.livekit.uniffi.*`, `uniffi.livekit_*`, `io.livekit:livekit-uniffi-android` | Prebuilt Rust core and its Maven coordinates. |
| `.livekit.cloud`, `.livekit.run`, `cloud-api.livekit.io` | Hosts and services the SDK talks to.                  |
| `lk.*` attribute keys (e.g. `lk.agent.state`)          | Wire protocol shared with the server and agents.      |
| This `wibechat/` folder                                | The rebrand rules have to name what they replace.     |

`rebrand.py` prints any other remaining mentions after each run.
