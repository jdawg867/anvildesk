# M8 renderer callback ownership contract (design gate)

**Status: BLOCKED for Lorie renderer integration.** The isolated unified-owner adapter has passed host tests and a standalone Samsung SM-G766U ARM64 device probe (2026-11-12), but remains **unwired to Lorie, JNI, or the production app**. Device evidence: `docs/development/m8-unified-owner-samsung-probe-readiness.md`.

Reference: draft PR #21, branch `feature/android-x11-display-input`. This
contract records the minimum safe ownership boundary before any native renderer
integration. It is not an implementation or permission to load `libXlorie.so`.

## Pinned renderer source observations

| Stage | Pinned upstream behavior | Hazard / required change |
|---|---|---|
| Allocate | `nativeInit()J` yields a raw `LorieViewResources*`-derived handle | Java/native handle must not outlive owner or be destroyed twice |
| Connect | `LorieViewResources::connect(jint fd)` calls `ALooper_addFd(ALooper_forThread(), ..., this)` | Registration uses the **calling thread's** looper; raw `this` is exposed as userdata |
| Dispatch | callback casts `data` to `LorieViewResources*` and invokes `xcallback(fd, events)` | A queued or executing callback can dereference freed memory |
| Disconnect | `xcallback` processes HANGUP/ERROR, removes FD, closes connection and notifies Java through a stored `JNIEnv*` | Stale FD guard and return-zero patch reduce one race, not callback quiescence; `JNIEnv*` is thread-affine |
| Destroy | destructor calls `ALooper_removeFd(ALooper_forThread(), connFd)`, destroys renderer and drops global JNI refs | Destruction may run on a **different** looper thread; removeFd alone does not prove callback completion |
| Free | `nativeDestroy(J)V` destroys `LorieViewResources` and calls `free(r)` | No independently verified worker join or protection against double-destroy before raw memory free |

See `scripts/audit-m8-renderer-lifecycle.py`,
`scripts/patch-m8-renderer-looper-disconnect.py` and their unit tests.
Source-observation markers are **not** runtime safety attestations.

## Mandatory ownership/order contract for a future adapter

1. **Construct owner before registration.** The renderer session must own a
   dedicated callback worker/looper identity, FD, and stable callback context.
   Acquire `JNIEnv*` per callback on the executing thread via `JavaVM`
   under an audited attach/detach policy; never share `JNIEnv*` between threads.
2. **Register with retained state.** Registration and deregistration must use
   the *same explicitly owned `ALooper*`*, not independent calls to
   `ALooper_forThread()`. No raw pointer to a destructible renderer may be
   directly dereferenced by an unprotected platform callback.
3. **Serialize state transitions.** Admission must close exactly once before
   shutdown. Active callbacks must hold a lease protecting renderer, Java refs
   and FD state. A stale-generation/FD callback must not mutate replacement
   connection state.
4. **Remove on the right looper.** Handle `ALooper_removeFd` negative errors
   by retaining all userdata and preventing new registration until a safe
   retry or fail-closed terminal state. A result of zero means not registered,
   **not** that every callback has finished.
5. **Prove callback-entry quiescence separately.** Drain active callback leases,
   then stop/wake and **join the owned polling worker**. Callback removal and
   lease drain alone do not prevent a callback already queued by Android from
   entering after the gate itself is destroyed. No self-join inside callback.
6. **Release in dependency order.** Only after verified worker join can the
   owner free callback context, destroy renderer resources, delete JNI global
   references and relinquish connection/transport ownership. Error/timeout
   must **not** free these resources optimistically.
7. **Make teardown terminal and idempotent.** Prevent repeat nativeDestroy,
   races with Surface callbacks, Java handle reuse and in-process restart
   unless a separately reviewed native Xorg restart contract exists.

## Relevant independent host components

- `scripts/m8-android-owned-looper-thread.cpp`: isolated one-FD worker,
  add/remove failure retry and joining; not connected to renderer.
- `scripts/m8-callback-token-registry.h`: host-only context/token model.
  A token and lease do **not** independently protect platform callback userdata
  or prove Android quiescence.
- `scripts/m8-native-callback-drain.h`: host-only lease admission/drain.
  It cannot prevent a not-yet-entered callback from touching a freed gate.
- `scripts/m8-looper-teardown-gate.h`: host-only fail-closed decisions.
  Intentionally has no state allowing native resource release.
- `scripts/m8-renderer-callback-gate-host-test.cpp`: host proof of admission
  drain, explicitly not evidence of NDK/JNI completion.

## Isolated unified-owner evidence and the renderer integration boundary

- `scripts/m8-renderer-callback-ownership-model.h` and
  `scripts/m8-renderer-owned-looper-adapter.h` compose the ownership
  state with a dedicated looper worker. These are isolated experiments,
  not included in the production renderer.
- Host fake-NDK tests cover removal failure/retry, active callback and
  competing shutdown, callback self-join rejection, addFd/prepare failure,
  automatic removal, idle stop, and heap userdata release after joining.
- A pinned standalone ARM64 executable passed on Samsung SM-G766U on
  2026-11-12. That proof covers **only** its own pipe callback and
  unified-owner shutdown path, not the upstream Lorie `activity.cpp`
  callback, `JNIEnv*`, surfaces, or renderer destruction.
- Existing `ALooper_forThread()` registration/destruction and raw
  `this` userdata remain present in the pinned Lorie source. The
  existing `nativeDestroy(J)V` path cannot be promoted merely by
  substituting an adapter object or adding a wait after `free(r)`.

### Required concrete renderer implementation order (not yet executed)

1. Make one **session owner** hold a callback context, the exact
   dedicated looper object, one registered FD, and a `JavaVM*`. A
   generation identifies the session and is validated before mutating
   connection or renderer state; the FD must not be reused as the
   generation.
2. Replace `ALooper_addFd(ALooper_forThread(), ..., this)` with
   registration against the explicit owned looper. The callback
   userdata points to a stable session context, **not** a bare
   `LorieViewResources*`. An active callback must retain a scoped
   lease protecting each resource it touches.
3. Replace the callback's stored `JNIEnv*` use with a reviewed
   per-callback-thread `JavaVM::GetEnv` / conditional
   `AttachCurrentThread` / conditional `DetachCurrentThread`
   protocol. Detach only threads attached by this callback path;
   never detach a thread already attached by another owner. Handle
   missing `JavaVM`, failed attach, pending exceptions and JNI
   references without leaving callback resources unbalanced.
4. Serialize `nativeDestroy` with Java/native handle invalidation and
   surface updates. Close callback admission, record FD removal
   success (including 0) or retain all userdata on error, drain
   leases, wake/stop and join the **owned** worker. The worker
   cannot join itself. No cleanup while a callback may still enter.
5. Only after join, delete JNI global references via a valid
   thread-attached `JNIEnv*`, destroy renderer resources and free
   callback userdata. Perform connection close exactly once; make
   duplicate destroy and stale generation no-ops with externally
   testable outcomes. Keep native Xorg lifetime separate.
6. First validate the actual patched Lorie `activity.cpp` in
   source-locked host tests and an isolated NDK package; require a
   separate gated Samsung run. Do not enable `libXlorie.so` until
   these checks and native server shutdown gates are complete.

**Specific unproven risks:** late Android callback entry after teardown,
native handle reuse/double destroy, Java surface callback races,
cross-thread JNI calls, native renderer calls executed on a worker
without documented thread-safety, and Xorg worker stop/restart.

Combining these models is **not** automatically safe: the eventual single
ownership graph must be reviewed against actual pinned renderer source, then
tested with sanitizer-backed host scenarios and an isolated ARM64 Android
diagnostic run.

## Promotion acceptance checklist

- [ ] Code review identifies each callback registration, raw userdata entry,
      FD removal, renderer destruction, Java global ref, and failure path.
- [ ] One explicit looper identity owns registration, callback dispatch and
      deregistration, with verified worker join before userdata release.
- [ ] Thread-affine JNI operations and partial-failure cleanup are reviewed.
- [ ] Tests inject queued callback, active callback, stale FD, add/remove
      failure, concurrent destroy and repeated stop without use-after-free.
- [ ] Native X11 server stop/join and process lifetime are independently
      proved; see `docs/m8-native-server-shutdown-contract.md`.
- [ ] Samsung ARM64 diagnostic package runs in separately authorized isolated
      process, with logs and source/artifact provenance. No production upgrade.

**M8_RENDERER_REGISTER_TEARDOWN_LOOPER_IDENTITY_VERIFIED=false**  
**M8_RENDERER_RAW_USERDATA_FREE_AFTER_JOIN_VERIFIED=false**  
**M8_RENDERER_JNI_LIFECYCLE_VERIFIED=false**  
**M8_NATIVE_LOAD_AUTHORIZED=false**
