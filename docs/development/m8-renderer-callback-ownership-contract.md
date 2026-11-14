# M8 renderer callback ownership contract (design gate)

**Status: BLOCKED for Lorie renderer integration.** The isolated unified-owner adapter passed a Samsung SM-G766U ARM64 device probe (2026-11-12), and the combined `RendererSessionOwnedLooper` callback trampoline/session context passed a separate Samsung ARM64 device probe (2026-11-13). **Neither is wired to Lorie, JNI, or the production app.** Evidence: `docs/development/m8-unified-owner-samsung-probe-readiness.md` and `docs/development/m8-composite-owner-samsung-device-acceptance.md`.

**New isolated JNI evidence (2026-11-14):** the explicitly opted-in
`M8CompositeJniDiagnosticInstrumentedTest` ran successfully on the Samsung
SM-G766U (`OK (1 test)`, `INSTRUMENTATION_CODE: -1`), reporting a real
`JavaVM*` worker-attachment / owned-looper join result. This is diagnostic
JNI scope execution, **not** patched Lorie callbacks or renderer lifecycle
validation. The original AnvilDesk package path remained unchanged per runner
checks. See `docs/development/m8-composite-jni-samsung-device-acceptance.md`
for artifact provenance, observed markers and limits. The Lorie and production
native-load gates remain blocked.

**Second Samsung diagnostic (2026-11-14):** a new isolated APK
(`e671a071`, artifact `14104411442`) passed AndroidJUnitRunner
`OK (1 test)` after calling both real JNI worker-attachment and
`runGlobalRefProbe(new Object())`. The latter exercises a real Java global
reference from the owned looper callback, preserves it before join and on a
deliberate pending JNI exception, then deletes it after the join.
The updated device-runner path guard passed; see
`docs/development/m8-composite-jni-samsung-device-acceptance.md`.
This does **not** verify Lorie's native handle, global references,
`nativeDestroy`, callback quiescence under all races, or Xorg lifecycle.

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

## Source-locked Lorie lifecycle review checkpoint (2026-11-14)

The pinned `activity.cpp` audit now scopes observations to
`LorieViewResources::connect(jint fd)` and to the registered
`nativeDestroy(J)V` method instead of accepting matching source tokens
elsewhere in the translation unit. Its unit tests reject a displaced
registration, unexpected callback userdata, duplicated raw callback entry,
and raw-free drift.

| Pinned method | Observed dependency | Required replacement before promotion |
|---|---|---|
| `connect(jint fd)` | Registers `this` using `ALooper_forThread()` | A session-owned looper and callback context with a retained lease; no raw destructible renderer userdata |
| Static native callback | Casts userdata directly to `LorieViewResources*` and calls `xcallback` | Generation-checked callback gate with entry quiescence proven by stop/join |
| `xcallback(fd, events)` | Uses retained `JNIEnv*` to invoke Java while touching renderer state | Acquire per-worker JNI env via `JavaVM`, preserve exceptions and Java reference lifetime |
| `~LorieViewResources()` | Removes FD via its caller thread's `ALooper_forThread()`, then destroys renderer and JNI globals | Remove the exact registered FD on the owned looper, drain/stop/join, then retire references/resources |
| `nativeDestroy(J)V` | Destructs raw object and calls `free(r)` | Idempotent handle invalidation and shutdown completion guarantee before free; account for Surface concurrency |

These are **blocker observations**, not assurances that a source patch would
be correct. A syntactic audit cannot prove the absence of callbacks already
queued in Android. The isolated Samsung JNI reference test does not supply
the missing Lorie-specific lifetime guarantee. Continue keeping
`M8_NATIVE_LOAD_AUTHORIZED=false` and PR #21 unmerged.

## Pinned callback JNI dependency inventory

The source-locked audit `scripts/audit-m8-lorie-callback-jni-inventory.py`
enumerates all **seven** event cases in the pinned `xcallback`, its
**17 JNI method calls**, and its renderer mutations. A separate upstream
workflow runs this audit on the checked-out pinned `activity.cpp` after the
isolated disconnect patch; mutation tests reject changed JNI call counts,
event dispatch, Java references or renderer operations.

The callback performs clipboard decode and Java callbacks, requests clipboard,
updates shared server state and buffers, handles focus changes, sync replies,
and recursive disconnects. Its single `DeleteLocalRef` call does not
establish balanced local-reference cleanup for all temporary objects.
No per-callback `GetEnv` / conditional attach, exception gate, reference
lease or renderer lease is established by the original implementation.

**Implication:** an owned-looper migration must cover the *entire callback*,
not merely exchange `ALooper_forThread()` for an explicit looper. Its
JNI locals, receiver/global references, renderer mutation and generation
state must be scoped to the same terminal callback-owner lifetime.
Source-inventory PASS means only that the known blocked source matches its
pin. It never permits production native loading.

## Isolated post-join retirement coordinator

`scripts/m8-renderer-session-retirement.h` now composes the already tested
worker-join operation, deferred JNI global-reference retirement, and an
explicit renderer-resource retire callback in a host-only session model.
Its sanitizer test injects worker join failure, JNI cleanup refusal,
renderer retirement failure, concurrent retries, and repeated shutdown.
A failed stage retains the remaining resources for an explicit retry and
never reports a successful terminal state prematurely.

**Owned-worker composite regression (new):**
`scripts/test_m8_owned_looper_host_execution.cpp` now also composes
`RendererSessionRetirement` with the real isolated
`RendererSessionOwnedLooper` implementation, backed by the existing fake
NDK looper functions. A held callback remains active while a second thread
begins shutdown; JNI reference and renderer-resource retirement must remain
blocked until that worker has joined. Terminal repeated shutdown must not
double-retire either resource. This is more concrete than an injected
`shutdownAndJoin() == true` fake, **but remains host execution with a fake
NDK platform**; it is not an Android callback race proof or Lorie integration.

**Failed looper deregistration / retry regression:** the same host composite now
injects two failing `ALooper_removeFd` attempts while a real owned worker
callback remains active. The retirement coordinator must refuse completion,
retain callback userdata and the JNI global reference, preserve renderer
resources, and reject a replacement registration. After clearing the simulated
failure, a separate thread retries shutdown; even after successful removal,
JNI and renderer retirement remain blocked until the held callback finishes
and the owned worker joins. Repeated retirement is checked for exactly-once
cleanup. These are fake-NDK host execution guarantees, not yet physical
Android or Lorie integration evidence.

**Limit:** this is a host protocol model with a synthetic worker join and
renderer cleanup. It is not wired to `RendererSessionOwnedLooper`, to
`LorieViewResources`, to a real Java Surface, or to Xorg. In particular,
a successful host test is not proof of queued platform-callback quiescence
or the absence of external Java handle races. No native-load gate changes.

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
- The complete isolated composite owner (`m8-renderer-session-owned-looper.h` and `m8-renderer-session-callback-context.h`) now also has real Android device smoke-test evidence. The user-supplied 2026-11-13 run passed callback dispatch, worker join and post-join context release, with pinned build/artifact provenance in `docs/development/m8-composite-owner-samsung-device-acceptance.md`. It does not establish renderer/JNI lifecycle safety.
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
