package dev.fallingcloud.dynamicelytra.trail;

import net.minecraft.world.phys.Vec3;

/**
 * Per-entity trail state: a history for each wing plus the last sampled shoulder position and flight
 * direction, from which airspeed and turn G-force are derived tick to tick.
 */
public final class EntityTrails {
    public final RibbonHistory left = new RibbonHistory();
    public final RibbonHistory right = new RibbonHistory();

    public Vec3 lastShoulder = null;
    public Vec3 lastDir = null;
    public long lastSeenTick = 0;

    /** Low-pass-filtered emission intensity so the ribbon thickens/thins smoothly instead of jumping. */
    public float smoothedIntensity = 0f;

    /**
     * Smoothed elytra wing angles (left wing; the right mirrors them), mirroring the EMA the vanilla
     * ElytraModel applies so the sampled wingtips track the rendered wings as they spread and fold.
     * Initialised to the folded/idle pose.
     */
    public float elytraX = (float) (Math.PI / 12);
    public float elytraZ = (float) (-Math.PI / 12);

    public boolean isDead() {
        return left.isEmpty() && right.isEmpty();
    }

    public void age(long now, int lifetime) {
        left.age(now, lifetime);
        right.age(now, lifetime);
    }
}
