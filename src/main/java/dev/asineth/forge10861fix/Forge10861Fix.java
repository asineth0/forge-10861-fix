package dev.asineth.forge10861fix;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Client-side memory-leak fix for Forge 1.20.1's modded-payload networking path
 * (MinecraftForge#10861).
 *
 * <p>See {@code dev.asineth.forge10861fix.mixin.SimpleChannelMixin} for the actual patch
 * and a full explanation. This class exists only so the jar is a valid FML mod container.
 */
@Mod(Forge10861Fix.MODID)
public class Forge10861Fix {

    public static final String MODID = "forge10861fix";

    public static final Logger LOGGER = LogUtils.getLogger();

    public Forge10861Fix() {
        LOGGER.info("[forge10861fix] Loaded: patching the Forge SimpleChannel clientbound payload leak (MinecraftForge#10861).");
    }
}
