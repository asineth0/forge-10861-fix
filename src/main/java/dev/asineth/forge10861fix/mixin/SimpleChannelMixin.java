package dev.asineth.forge10861fix.mixin;

import io.netty.util.IllegalReferenceCountException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.simple.SimpleChannel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/**
 * Fixes the unbounded direct-memory leak in Forge 1.20.1's modded-payload handling.
 *
 * <h2>The bug</h2>
 * When a {@code ClientboundCustomPayloadPacket} arrives, Forge dispatches it through
 * {@code NetworkHooks.onCustomPayload -> NetworkInstance.dispatch -> SimpleChannel
 * .networkEventListener}. The {@code NetworkEvent} is constructed from
 * {@code ICustomPacket.getInternalData()}, and for the clientbound case Forge's patch does:
 *
 * <pre>
 *   public FriendlyByteBuf m_132045_() {          // getInternalData()
 *       return new FriendlyByteBuf(this.f_132030_.copy());   // &lt;-- fresh pooled DIRECT copy
 *   }
 * </pre>
 *
 * That new buffer is stored on {@code NetworkEvent.payload} and handed to every registered
 * SimpleChannel listener, but it is <b>never released</b> on the modded-payload path. Each
 * received custom payload therefore leaks one direct ByteBuf. Because every mod that uses
 * {@code SimpleChannel} dispatches through here, the leak grows with normal gameplay; on a
 * large GregTech pack the dominant traffic is machine-sync (LDLib
 * {@code SPacketManagedPayload}, bundled inside GT), which is why the leak rate tracks
 * machine activity.
 *
 * <p>Confirmed empirically on a live modded client with Netty's leak detector
 * ({@code -Dio.netty.leakDetection.level=PARANOID}) and JFR
 * {@code jdk.DirectBufferStatistics}: the direct-buffer pool climbed past 8 GB against a
 * 16 GB cap while the Java heap stayed flat, ending in
 * {@code OutOfMemoryError: Cannot reserve ... direct buffer memory}.
 *
 * <h2>Why this injection point</h2>
 * {@code SimpleChannel#networkEventListener(NetworkEvent)} is used rather than
 * {@code IndexedMessageCodec#tryDecode} because:
 * <ul>
 *   <li>its signature contains no package-private types, so no {@code @Coerce}/split-package
 *       gymnastics are needed;</li>
 *   <li>it fires on every normal return of the codec's {@code consume}, including the
 *       early-return error branches that {@code tryDecode} never reaches;</li>
 *   <li>{@code NetworkEvent.getPayload()} is the exact buffer that leaks.</li>
 * </ul>
 *
 * <h2>Why the release is gated on direction</h2>
 * Only the <b>clientbound</b> path mints an owned copy. ({@code ServerboundCustomPayloadPacket
 * .getInternalData()} returns its own buffer, and the login {@code ClientboundCustomQueryPacket}
 * is likewise not owned.) Releasing those would double-free and throw
 * {@code IllegalReferenceCountException: refCnt: 0, decrement: 1} - which is exactly the bug
 * that Forge's own login handshake (also a SimpleChannel) would trigger. Restricting to
 * {@link NetworkDirection#PLAY_TO_CLIENT} keeps the release to precisely the owned copy.
 */
@Mixin(value = SimpleChannel.class, remap = false)
public abstract class SimpleChannelMixin {

    @Inject(
        method = "networkEventListener",
        at = @At("RETURN"),
        remap = false,
        require = 1
    )
    private void forge10861fix$releaseClientboundPayload(NetworkEvent event, CallbackInfo ci) {
        if (event == null) {
            return;
        }

        // Skip registry-change events, which carry no payload.
        Supplier<NetworkEvent.Context> sourceSupplier = event.getSource();
        if (sourceSupplier == null) {
            return;
        }
        NetworkEvent.Context context = sourceSupplier.get();
        if (context == null || context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
            return;
        }

        FriendlyByteBuf payload = event.getPayload();
        if (payload == null) {
            return;
        }

        // FriendlyByteBuf extends ByteBuf and delegates refCnt()/release() to its wrapped
        // "source" buffer, so this releases the real pooled copy exactly once.
        //
        // refCnt() is a plain volatile read and is safe at 0; release() is NOT - it throws
        // IllegalReferenceCountException once the count hits zero. The guard is therefore
        // mandatory, not merely defensive (a mod decoder may already have released it).
        try {
            if (payload.refCnt() > 0) {
                payload.release();
            }
        } catch (IllegalReferenceCountException ignored) {
            // Lost a race with another releaser - nothing left to do.
        } catch (Throwable ignored) {
            // Never let cleanup break packet handling.
        }
    }
}
