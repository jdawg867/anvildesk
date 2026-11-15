# M8 checkpoint: Lorie callback migration entry/exit review (2026-11-15)

Status: **design + source audit only — NOT integrated or authorized to load**.

Branch: `feature/android-x11-display-input`; draft PR #21. This checkpoint
builds on GitHub Actions passing at `4cbc81fd` (including host owned-looper,
callback-gate, and production no-load policy). Host success is not Android
callback quiescence proof.

## Ownership mapping for the pinned `activity.cpp`

| Upstream entry/exit | Existing isolated AnvilDesk component | Non-negotiable Lorie migration |
|---|---|---|
| `nativeInit()J` returns raw `LorieViewResources*` | `RendererSessionOwnedLooper`; `RendererReplacementHandoff` | Introduce an invalidatable generation/handle registry before touching renderer; reject concurrent `nativeDestroy` and Surface operations |
| `connect(jint fd)`: `ALooper_addFd(ALooper_forThread(), ..., this)` | `RendererSessionOwnedLooper::registerCallback` | Register a dedicated-owned-looper trampoline with independently retained callback context, **never** destructible renderer `this` as platform userdata |
| Static looper lambda casts `data` and calls `xcallback` | `RendererSessionCallbackContext::enter` | Validate descriptor and unique generation, close admission before removal, retain renderer/JNI owners for full event processing |
| `xcallback` with seven event cases and 17 JNI calls | `ScopedAndroidCallbackEnv`; global-reference retirement prototypes | Acquire thread-local `JNIEnv*` from `JavaVM*` for *every* callback; check/handle pending exceptions and local refs on every path; do not borrow a stored `JNIEnv*` |
| Disconnect/HANGUP recursively calls `xcallback` | Owned-looper return-zero and removal/retry tests | Eliminate recursive teardown paths and reject stale FD. Maintain clipboard, renderer and Java event semantics while the event handler is split |
| Destructor removes FD with `ALooper_forThread()` | `RendererSessionOwnedLooper::shutdownAndJoin` | Stop admission, deregister using the **registered looper**, retry failed removal without freeing userdata, join worker and drain leases **before** retiring Java globals and renderer |
| `nativeDestroy(J)V` calls destructor and `free(r)` | `RendererSessionRetirement`; replacement handoff | Invalidatable handle; wait for in-flight JNI/Surface users, exactly-once terminal cleanup; only then reclaim renderer and callback state |

## Review gates (in order)

1. Keep `M8NativeLoadingPolicy.mayLoadLibrary` returning `false`, `:m8x11`
   service disabled/non-exported, and PR #21 draft.
2. Record a **real handle/Surface lifetime model**; existing callback tests
   do not cover arbitrary concurrent Java calls into a destroyed renderer.
3. Patch upstream `connect` and teardown in an **isolated build artifact**
   pinned to the exact reviewed source; never package/load it in production.
4. Refactor **all seven** callback branches and **all 17** JNI operations with
   per-callback env and reference/exception ownership. Re-run the pinned source
   audit after reviewing intentional source drift; don't relax drift checks
   merely to make the patch pass.
5. Exercise failed deregistration, stale FD reuse, concurrent destroy, Java
   exception, and worker join against instrumented Android callbacks.
6. Separately audit Xorg shutdown, JNI_OnLoad rollback, native server isolation,
   and reproducible device artifacts. No implicit production promotion.

## Current verified / blocked boundaries

- Isolated owned-worker, callback gate and retirement regression workflows:
  **host CI PASS**, not real Lorie ownership.
- Samsung diagnostics: **limited isolated device PASS** in previous acceptance
  records, not Lorie/SURFACE runtime acceptance.
- Lorie `this` userdata and thread-bound stored `JNIEnv*`: **BLOCKED**.
- Verified full `nativeDestroy`/Surface concurrency: **BLOCKED**.
- Native server start/stop and safe repeat lifecycle: **BLOCKED**.
- `M8_NATIVE_LOAD_AUTHORIZED=false`.

The next executable change should introduce and test a *renderer handle
invalidation/lease model*, isolated from upstream Lorie. Only afterward should
the pinned Lorie `nativeInit`/`nativeDestroy` wiring be attempted.
