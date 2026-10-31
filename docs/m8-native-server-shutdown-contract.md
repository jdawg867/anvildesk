# M8 native server shutdown contract (design gate)

Status: **BLOCKED — design only, not a working stop API**.

The pinned Termux:X11 native entrypoint runs `dix_main()` in a pthread and
currently uses process-global JNI state (`thiz`, `serverEnv`, `argv`).
Our no-TCP patch replaces `exit(dix_main(...))` with a normal thread return.
That prevents a direct process exit but **does not terminate a running X server**.

## Required lifecycle

1. An isolated server owner establishes one serialized state machine:
   `IDLE -> STARTING -> RUNNING -> STOP_REQUESTED -> JOINED -> CLEANED`.
   Any uncertain native failure transitions to terminal `FAILED`.
2. Startup owns a single pthread handle and cannot report RUNNING until its
   actual X11 readiness and authenticated socket connection are verified.
3. A stop request must use an Xorg-supported, reviewed in-server mechanism.
   No asynchronous `pthread_cancel`, signals to the whole Android process,
   `exit()`, or arbitrary closing of process-wide descriptors.
4. The owner waits for `dix_main()` to exit via bounded, verified thread join.
   A timeout does **not** free globals or permit restarting in-process.
5. Only after a successful join may the owner clean process-global JNI refs,
   `argv`, native X11 resources and the app-private transport workspace.
6. All Android Surface renderer resources are revoked and destroyed before
   releasing any X11 connection or restarting the native server.
7. Device acceptance must confirm repeated start/stop, failure injection,
   rotation, guest disconnect, and app shutdown without terminating the app
   or leaking resources.

## Current blockers

- No audited native stop signal dispatched on the Xorg server thread.
- No retained pthread ownership with a verified join/timeout contract.
- No proven reinitialization support for process-global Xorg state.
- No verified JNI global reference release on partial failure.
- No physical-device proof of actual server shutdown.

**Do not wire `System.loadLibrary("Xlorie")`, call native `start()`,
enable the M8 production variant, or install over the existing Samsung app.**
This design is intended as a review checklist for a future isolated native
implementation and does not imply upstream supports in-process restart.
