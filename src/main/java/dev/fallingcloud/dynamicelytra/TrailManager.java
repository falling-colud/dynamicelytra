package dev.fallingcloud.dynamicelytra;

import dev.fallingcloud.dynamicelytra.render.RibbonRenderer;
import dev.fallingcloud.dynamicelytra.trail.EntityTrails;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Owns every entity's trail state. It samples eligible gliders each client tick and draws their ribbons
 * during the level render. Two game-bus events, no mixins.
 */
public final class TrailManager {
    public static final TrailManager INSTANCE = new TrailManager();

    private final Map<UUID, EntityTrails> trails = new HashMap<>();
    private long tick = 0;

    private TrailManager() {}

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        DynamicElytraConfig cfg = DynamicElytraConfig.get();
        Minecraft mc = Minecraft.getInstance();
        if (!cfg.enabled() || mc.level == null || mc.player == null || mc.isPaused()) {
            if (!trails.isEmpty()) trails.clear();
            return;
        }
        tick++;
        long now = tick;
        int lifetime = cfg.lifetimeTicks();

        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le)) continue;
            boolean isSelf = e == mc.player;
            if (!isSelf && !cfg.showOthers()) continue;

            boolean gliding = le.isFallFlying();
            boolean riptide = cfg.riptideTrails() && le.isAutoSpinAttack();
            boolean fastFall = cfg.fallTrails() && !le.onGround() && le.getDeltaMovement().y < -0.6 && !gliding;
            if (!(gliding || riptide || fastFall)) continue;

            EntityTrails t = trails.computeIfAbsent(e.getUUID(), k -> new EntityTrails());
            dev.fallingcloud.dynamicelytra.trail.WingtipSampler.sample(e, cfg, t, now);
        }

        Iterator<Map.Entry<UUID, EntityTrails>> it = trails.entrySet().iterator();
        while (it.hasNext()) {
            EntityTrails t = it.next().getValue();
            t.age(now, lifetime);
            if (t.isDead() && now - t.lastSeenTick > lifetime) {
                it.remove();
            }
        }
    }

    @SubscribeEvent
    public void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (trails.isEmpty()) return;
        DynamicElytraConfig cfg = DynamicElytraConfig.get();
        if (!cfg.enabled()) return;
        if (Minecraft.getInstance().level == null) return;

        Minecraft mc = Minecraft.getInstance();
        Vec3 cam = event.getCamera().getPosition();
        Matrix4f pose = event.getPoseStack().last().pose();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        long now = tick;

        // Your own trail draws in first person too: the renderer fades geometry near the camera, so the wings'
        // vapor streams away behind you instead of smearing across the lens.
        boolean firstPerson = mc.options.getCameraType().isFirstPerson();
        UUID selfId = mc.player != null ? mc.player.getUUID() : null;

        for (Map.Entry<UUID, EntityTrails> entry : trails.entrySet()) {
            boolean isSelf = entry.getKey().equals(selfId);
            if (firstPerson && isSelf && !cfg.firstPersonTrails()) continue;
            EntityTrails t = entry.getValue();
            // side picks the inboard direction for the rough wash: the renderer offsets by perp*(-side),
            // where perp = tangent x up = the entity's RIGHT. Left wingtip tucks toward the body (+right,
            // side=-1); right wingtip tucks -right (side=+1).
            RibbonRenderer.draw(t.left, -1f, pose, cam, cfg, now, partialTick);
            RibbonRenderer.draw(t.right, +1f, pose, cam, cfg, now, partialTick);
        }
    }
}
