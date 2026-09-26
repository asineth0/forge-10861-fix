# Verification

## Environment

- Instance: Monifactory `0.13.7`, Forge `1.20.1-47.4.13`, 221 mods (incl. GregTech Modern 7.5.3,
  Embeddium 0.3.31, Oculus 1.8.0, LDLib bundled in GT).
- Client: `-Xmx16g`, **direct-memory cap 24 GB** (`-XX:MaxDirectMemorySize=24g`).
- Method: JDK Flight Recorder `jdk.DirectBufferStatistics` sampled every 2 s (`verify.jfr`),
  plus live `jcmd` dumps. 299 samples over the recorded window.

## Pre-fix baseline (same instance, before the mod)

Measured on the live client with JFR:

| Metric | Value |
|---|---|
| Direct pool start | 6.5 GB |
| Direct pool later | 8.3 GB |
| Growth rate | **~100–120 MB/min** |
| Trend | monotonic, never decreases |
| Heap | ~5 GB, flat (i.e. the heap was *not* the problem) |
| Netty leak detector | `LEAK: ByteBuf.release() was not called`, access records at LDLib `SPacketManagedPayload.decode` (3241), LaserIO, JourneyMap, Curios |
| Final symptom | `OutOfMemoryError: Cannot reserve ... direct buffer memory` (allocated: 17 179 709 809, limit: 17 179 869 184) |

## Post-fix result

299 samples, 10 minutes (t=21:08:59 → t=21:18:55):

```
FIRST: used = 23.6 MB   count = 179
LAST : used = 20.9 MB   count = 114
```

Distribution of `memoryUsed` across all 299 samples:

```
20.9 MB   2      22.6 MB  11      23.2 MB  12
21.2 MB  42      22.7 MB  31      23.3 MB  14
21.3 MB  30      22.8 MB  20      23.6 MB   1
21.4 MB  18      22.9 MB   3      23.7 MB   3
21.8 MB   9      23.0 MB   5      23.8 MB   5
21.9 MB  30      23.1 MB   5      23.9 MB   2
22.0 MB  32      24.0 MB   5
```

**Peak 24.0 MB. Final 20.9 MB.** The pool oscillates in a ~3 MB band and nets *negative*
over the session (normal GC reclaim), instead of climbing without bound.

At the observed pre-fix rate of ~100–120 MB/min, this window would have added roughly
**1 GB**. It added effectively zero.

## Boot / apply checks

```
[debug.log] Mixing SimpleChannelMixin from forge10861fix.mixins.json
            into net.minecraftforge.network.simple.SimpleChannel
[latest.log] [forge10861fix] Loaded: patching the Forge SimpleChannel clientbound payload leak.
[latest.log] Sound engine started
```

No `Mixin apply failed`, no `hs_err_pid*.log`, no `ResolutionException`.

Note: the **Connectivity** mod also mixes `SimpleChannel`; both applied cleanly and coexist.

## Conclusion

The fix eliminates the measured leak. Direct buffer usage is now bounded and stable during
normal play, versus unbounded growth to OOM before.

## Caveats

- This is a client-side stopgap for an upstream Forge bug (MinecraftForge#10861). The proper
  fix belongs in Forge; once Forge releases it, this mod becomes redundant.
- The release is deliberately limited to `NetworkDirection.PLAY_TO_CLIENT`. If some mod
  `retain()`s the payload itself, that packet still leaks — that would be a mod bug and cannot
  be fixed safely here.
- No long-duration (hours) soak test was performed; the 10-minute JFR window plus user
  observation was sufficient to show the trend is flat rather than linear.
