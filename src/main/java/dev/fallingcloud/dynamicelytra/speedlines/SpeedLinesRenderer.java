package dev.fallingcloud.dynamicelytra.speedlines;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.fallingcloud.dynamicelytra.DynamicElytraConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.joml.Matrix4f;

/**
 * First-person "speed lines" overlay — the screen-space companion to Dynamic Elytra's world-space wingtip trails
 * (this is the former standalone Slipstream mod, folded in).
 *
 * <p>Samples the local player's speed and movement source each tick and draws a screen-space overlay each
 * frame that radiates from where you're heading and blooms the faster you move. Everything is immediate-mode
 * {@code POSITION_COLOR} geometry (plus a couple of gradient fills for the vignette), drawn just before the
 * HUD so it sits over the world but under the HUD. Reads {@link DynamicElytraConfig#speedLines}.
 */
public final class SpeedLinesRenderer {
    public static final SpeedLinesRenderer INSTANCE = new SpeedLinesRenderer();

    private double prevX, prevZ;
    private boolean hasPrev = false;
    private float smoothedSpeed = 0f;
    private float intensity = 0f; // eased 0..1

    private SpeedLinesRenderer() {}

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        DynamicElytraConfig.SpeedLines cfg = DynamicElytraConfig.get().speedLines;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (!cfg.enabled() || p == null || mc.isPaused()) {
            intensity *= 0.8f;
            hasPrev = false;
            return;
        }

        if (!hasPrev) { prevX = p.getX(); prevZ = p.getZ(); hasPrev = true; }
        double dx = p.getX() - prevX, dz = p.getZ() - prevZ;
        prevX = p.getX(); prevZ = p.getZ();
        float speed = (float) Math.min(Math.sqrt(dx * dx + dz * dz) * 20.0, 80.0); // clamp teleports
        smoothedSpeed += (speed - smoothedSpeed) * 0.4f;

        float mult;
        if (p.isFallFlying()) mult = cfg.elytraMul();
        else if (p.isAutoSpinAttack()) mult = cfg.riptideMul();
        else if (p.getVehicle() != null) mult = cfg.vehicleMul();
        else if (!p.onGround() && p.getDeltaMovement().y < -0.4) mult = cfg.fallMul();
        else if (p.isSprinting()) mult = cfg.sprintMul();
        else mult = 0f;

        float target = clamp((smoothedSpeed - cfg.threshold()) / 20.0f, 0f, 1f) * mult;
        target = clamp(target, 0f, 1f);
        intensity += (target - intensity) * 0.15f;
    }

    @SubscribeEvent
    public void onRenderGui(RenderGuiEvent.Pre event) {
        DynamicElytraConfig.SpeedLines cfg = DynamicElytraConfig.get().speedLines;
        if (!cfg.enabled() || intensity < 0.02f) return;

        GuiGraphics g = event.getGuiGraphics();
        int w = g.guiWidth(), h = g.guiHeight();
        Minecraft mc = Minecraft.getInstance();
        float clock = (mc.level != null ? mc.level.getGameTime() : 0)
                + event.getPartialTick().getGameTimeDeltaPartialTick(false);

        if (cfg.style() == DynamicElytraConfig.Style.VIGNETTE) {
            drawVignette(g, w, h, cfg);
        } else {
            drawLines(g, w, h, cfg, clock, cfg.style() == DynamicElytraConfig.Style.SMEAR);
        }
    }

    private void drawVignette(GuiGraphics g, int w, int h, DynamicElytraConfig.SpeedLines cfg) {
        int col = cfg.color();
        int a = (int) (clamp(cfg.maxOpacity() * intensity, 0f, 1f) * 170) << 24;
        int solid = a | (col & 0xFFFFFF);
        int band = (int) (h * 0.30f);
        g.fillGradient(0, 0, w, band, solid, col & 0xFFFFFF);
        g.fillGradient(0, h - band, w, h, col & 0xFFFFFF, solid);
    }

    private void drawLines(GuiGraphics g, int w, int h, DynamicElytraConfig.SpeedLines cfg, float clock, boolean smear) {
        float cx = w * 0.5f;
        float cy = h * 0.5f + h * cfg.focusOffset();
        float diag = (float) Math.sqrt((double) w * w + (double) h * h);
        float r0 = diag * 0.13f;
        float r1 = diag * 0.66f * cfg.lineLength();

        int baseLines = smear ? 90 : 55;
        int lines = (int) (baseLines * cfg.density() * Math.max(0.3f, intensity));
        if (lines < 1) return;

        int color = cfg.color();
        int cr = (color >> 16) & 0xFF, cg = (color >> 8) & 0xFF, cb = color & 0xFF;
        float maxA = clamp(cfg.maxOpacity() * intensity, 0f, 1f);
        float widthMul = smear ? 3.2f : 1.0f;

        Matrix4f mat = g.pose().last().pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder bb = tess.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        for (int i = 0; i < lines; i++) {
            float jit = hash(i);
            float ang = i * 2.399963f + clock * 0.02f;                 // golden-angle spread, slow rotate
            float phase = frac(clock * 0.055f * (0.6f + jit) + jit);   // 0..1 rush outward
            float rr0 = lerp(r0, r1, phase);
            float len = r1 * 0.14f * (0.6f + jit);
            float rr1 = Math.min(r1, rr0 + len);
            float a = maxA * (float) Math.sin(phase * Math.PI) * (0.55f + 0.45f * jit);
            if (a <= 0.01f) continue;

            float dxu = (float) Math.cos(ang), dyu = (float) Math.sin(ang);
            float px = -dyu, py = dxu;
            float wIn = 1.6f * widthMul, wOut = 0.5f * widthMul;
            float ix = cx + dxu * rr0, iy = cy + dyu * rr0;
            float ox = cx + dxu * rr1, oy = cy + dyu * rr1;
            int aIn = (int) (clamp(a * 0.12f, 0f, 1f) * 255);
            int aOut = (int) (clamp(a, 0f, 1f) * 255);

            // tapered quad = 2 triangles, bright at the leading (outer) edge
            vtx(bb, mat, ix + px * wIn, iy + py * wIn, cr, cg, cb, aIn);
            vtx(bb, mat, ix - px * wIn, iy - py * wIn, cr, cg, cb, aIn);
            vtx(bb, mat, ox + px * wOut, oy + py * wOut, cr, cg, cb, aOut);
            vtx(bb, mat, ix - px * wIn, iy - py * wIn, cr, cg, cb, aIn);
            vtx(bb, mat, ox - px * wOut, oy - py * wOut, cr, cg, cb, aOut);
            vtx(bb, mat, ox + px * wOut, oy + py * wOut, cr, cg, cb, aOut);
        }

        MeshData mesh = bb.build(); // null if no vertices were emitted
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }

        RenderSystem.disableBlend();
        RenderSystem.enableCull();
    }

    private static void vtx(BufferBuilder bb, Matrix4f mat, float x, float y, int r, int g, int b, int a) {
        bb.addVertex(mat, x, y, 0f).setColor(r, g, b, a);
    }

    private static float hash(int i) {
        float s = (float) Math.sin(i * 12.9898f) * 43758.5453f;
        return s - (float) Math.floor(s);
    }

    private static float frac(float v) { return v - (float) Math.floor(v); }
    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : Math.min(v, hi); }
}
