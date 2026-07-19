package dev.fallingcloud.dynamicelytra.trail;

import net.minecraft.world.phys.Vec3;

/**
 * One emitted wingtip point. Its intensity and width are frozen at emit time from that tick's airspeed and
 * turn G-force, so a burst of dense vapor from a hard bank stays visible as it ages down the ribbon.
 */
public record TrailPoint(Vec3 pos, long birthTick, float intensity, float width) {}
