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
./sync.sh --yes         # push without the confirmation prompt
```

The sync does the following:

1. Adds the `upstream` remote if missing, then fetches `origin` and `upstream`.
2. Fast-forwards `main` to `upstream/main`. It stops if `main` has any commit
   that is not in upstream.
3. Exports `main` and vendors the `.proto` files from the `protocol` submodule into
   `protocol/protobufs`. The generated branch has no submodule.
4. Runs `rebrand.py`, which removes upstream release tooling, rewrites names and
   paths, applies `overlay/`, appends to `NOTICE`, then runs `customize.sh`.
5. Copies this `wibechat/` folder (including uncommitted edits) and `sync.sh` in as-is.
6. Commits the tree on top of the previous generated commit, with a
   `Synced-From: <main sha>` trailer.
7. Builds it with `./gradlew assembleDebug`.
8. Asks for confirmation, then pushes `main` (if it moved) and `wibechat-rebrand`.

The first sync replaces the history of `origin/wibechat-rebrand` with a new
history that has no upstream commits (force-with-lease push). Later syncs are
normal fast-forward pushes.

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
