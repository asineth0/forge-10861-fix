# forge10861fix — Forge #10861 Fix

Fixes the Forge 1.20.1 modded-payload direct-memory leak (`MinecraftForge#10861`).

Fixes the unbounded off-heap **direct memory** leak in Forge 1.20.1's modded-payload
(`SimpleChannel`) handling. This is the leak that makes big GregTech packs (e.g.
Monifactory) slowly leak native memory on the client until the JVM throws:

```
java.lang.OutOfMemoryError: Cannot reserve <n> bytes of direct buffer memory
```

## The bug

When a `ClientboundCustomPayloadPacket` arrives, Forge builds the `NetworkEvent` from
`ICustomPacket.getInternalData()`. For the clientbound case, Forge's patch does:

```java
public FriendlyByteBuf m_132045_() {                    // getInternalData()
    return new FriendlyByteBuf(this.f_132030_.copy());  // fresh pooled DIRECT copy
}
```

That copy is stored on `NetworkEvent.payload` and handed to every registered listener,
but **it is never released** on the modded-payload path. Every mod that uses
`SimpleChannel` therefore leaks one direct `ByteBuf` per received packet. Verified on a
live client with Netty's leak detector, where the dominant traffic was LDLib's
machine-sync packets (bundled inside GregTech), plus LaserIO, JourneyMap and Curios.

Tracked upstream as **MinecraftForge#10861** (open, unfixed as of Forge 47.4.23).

## The fix

`SimpleChannelMixin` injects at the return of
`net.minecraftforge.network.simple.SimpleChannel#networkEventListener(NetworkEvent)` and
releases the payload, gated to `NetworkDirection.PLAY_TO_CLIENT`:

- Only the clientbound path mints an owned copy. Serverbound and login paths return
  their own buffers, so releasing those would double-free (`IllegalReferenceCountException`).
- `refCnt() > 0` guard, because `release()` on an already-released buffer throws.
- `FriendlyByteBuf.release()`/`refCnt()` are overrides that delegate to the wrapped
  `source` buffer (SRG `f_130049_`), so this frees the real pooled copy exactly once.

`networkEventListener` is used instead of `IndexedMessageCodec#tryDecode` because its
signature has no package-private types (no split-package or `@Coerce` problems) and it
also covers the codec's early-return error paths.

## Building

Requires a **real JDK 17** (a JRE fails: *"Java compiler is not available"*). This
machine pins one in `gradle.properties`:

```
org.gradle.java.home=C\:\\Program Files\\Microsoft\\jdk-17.0.15.6-hotspot
```

```
./gradlew build
# -> build/libs/forge10861fix-1.0.0.jar
```

Built against Forge `1.20.1-47.4.13` with MixinGradle `0.7.38` / Mixin `0.8.5`.

## Installing

Drop the jar in the instance's `mods/` folder. Client-side fix; safe on a dedicated
server too (the mixin is in the common list and simply no-ops when the direction is not
`PLAY_TO_CLIENT`).

## Status

- Builds cleanly; mixin applies to `net.minecraftforge.network.simple.SimpleChannel`.
- Client boots to the main menu with no mixin errors and no `hs_err`.
- **Leak verified fixed** — direct-buffer pool stayed in a 20.9–24.0 MB band across a
  299-sample JFR session, versus ~100–120 MB/min unbounded growth before the mod.
  Full numbers in [`docs/verification.md`](docs/verification.md).
