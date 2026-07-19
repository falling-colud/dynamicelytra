package dev.fallingcloud.dynamicelytra;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dynamic Elytra — jet-style wingtip vapor trails for the elytra, plus first-person speed lines when you move fast.
 *
 * <p><b>Trails (third-person / world-space).</b> While you glide, two translucent contrail ribbons stream off
 * your wingtips. They stay thin in a straight line and bloom into dense white vapor when you pull a hard bank,
 * driven by live airspeed and turn-rate. Optional trails fire on fast free-falls, riptide launches, and other
 * players' flights so a busy sky fills with crisscrossing wakes. Each wing draws in two passes over one
 * Catmull-Rom-splined path: a broad, rough-edged translucent vapor wash tucked slightly inboard, plus a thin
 * crisp contrail line riding the wingtip edge — the plane look.
 *
 * <p><b>Speed lines (first-person).</b> The former standalone Slipstream mod, folded in: anime-style speed
 * lines that radiate from where you're heading and bloom the faster you move, scaled per movement source
 * (sprint / falling / vehicle / riptide / elytra). Shaderless screen-space geometry drawn over the finished
 * frame, so it shows over Iris too.
 *
 * <p>Both effects are pure vanilla immediate-mode geometry (no mixins), driven by NeoForge client events.
 */
@Mod(value = DynamicElytra.MOD_ID, dist = Dist.CLIENT)
public final class DynamicElytra {
    public static final String MOD_ID = "dynamicelytra";
    public static final String VERSION = "3.0.0";
    public static final Logger LOGGER = LoggerFactory.getLogger("Dynamic Elytra");

    public DynamicElytra(IEventBus modEventBus, ModContainer modContainer) {
        // Settings live in the NeoForge mods config screen (Mods -> Dynamic Elytra -> Config).
        modContainer.registerConfig(ModConfig.Type.CLIENT, DynamicElytraConfig.SPEC);
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        dev.fallingcloud.dynamicelytra.render.TrailShaders.register(modEventBus);
        NeoForge.EVENT_BUS.register(TrailManager.INSTANCE);
        NeoForge.EVENT_BUS.register(dev.fallingcloud.dynamicelytra.speedlines.SpeedLinesRenderer.INSTANCE);
        LOGGER.info("[Dynamic Elytra] Initialised.");
    }
}
