package dev.fallingcloud.dynamicelytra.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import java.io.IOException;

/**
 * Vapor's core shaders. Both are copies of vanilla's {@code position_tex_color} differing only in their alpha
 * cutoff, because vanilla's fixed 0.1 cutoff is wrong at both ends for a contrail:
 *
 * <ul>
 *   <li>{@code vapor_trail} (cutoff 0.01) — the colour pass. Vanilla's 0.1 chewed the soft vapor wash (which
 *       peaks near 0.27) into a flickering speckle and erased faint trails outright.</li>
 *   <li>{@code vapor_line_depth} (cutoff 0.4) — the tip line's depth-only pass, so only its opaque core
 *       displaces volumetric clouds.</li>
 * </ul>
 *
 * Registered on the mod bus; if either fails to load the renderer falls back to vanilla rather than crashing.
 */
public final class TrailShaders {

    private static ShaderInstance trailShader;
    private static ShaderInstance lineDepthShader;

    private TrailShaders() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(TrailShaders::onRegisterShaders);
    }

    private static void onRegisterShaders(RegisterShadersEvent event) {
        trailShader = load(event, "vapor_trail", s -> trailShader = s);
        lineDepthShader = load(event, "vapor_line_depth", s -> lineDepthShader = s);
    }

    private static ShaderInstance load(RegisterShadersEvent event, String name,
                                       java.util.function.Consumer<ShaderInstance> sink) {
        try {
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath("dynamicelytra", name),
                            DefaultVertexFormat.POSITION_TEX_COLOR),
                    sink::accept);
        } catch (IOException e) {
            dev.fallingcloud.dynamicelytra.DynamicElytra.LOGGER.error("[Dynamic Elytra] Failed to load shader '{}'; falling back to the "
                    + "vanilla one (trails will have a hard alpha edge)", name, e);
        }
        return null;
    }

    /** The soft colour shader, or null before it loads / if it failed (callers fall back). */
    public static ShaderInstance trail() {
        return trailShader;
    }

    /** The tip line's depth-only shader, or null before it loads / if it failed. */
    public static ShaderInstance lineDepth() {
        return lineDepthShader;
    }
}
