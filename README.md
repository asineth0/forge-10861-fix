# forge-10861-fix

Fixes the direct-memory leak in Forge 1.20.1's `SimpleChannel` custom-payload handling
([MinecraftForge#10861](https://github.com/MinecraftForge/MinecraftForge/issues/10861)).
Without it, modded clients leak off-heap direct memory until the JVM throws
`OutOfMemoryError: Cannot reserve ... direct buffer memory`.

## The bug

On an incoming `ClientboundCustomPayloadPacket`, Forge builds the `NetworkEvent` from
`ICustomPacket.getInternalData()`, which for the clientbound case returns a fresh pooled
direct copy:

```java
public FriendlyByteBuf m_132045_() {                    // getInternalData()
    return new FriendlyByteBuf(this.f_132030_.copy());  // pooled DIRECT copy
}
```

That copy is stored on `NetworkEvent.payload` and passed to every `SimpleChannel`
listener, but is never released. Every playerbound custom payload leaks one direct buffer.

## The fix

`SimpleChannelMixin` injects at the return of `SimpleChannel#networkEventListener` and
releases the payload when the direction is `PLAY_TO_CLIENT`. Serverbound and login paths
return their own buffers, so they are left alone (releasing them would throw
`IllegalReferenceCountException`). A `refCnt() > 0` guard avoids releasing a buffer a mod
decoder already freed.

## Building

```
./gradlew build
```

## Status

Works pretty good, tested for a few hours and the leak seems to be completely fixed. You can check if it's working by pressing F3 and looking in the top-right at the "Direct Buffers" number, if it's going up rapidly then it's probably leaking.
