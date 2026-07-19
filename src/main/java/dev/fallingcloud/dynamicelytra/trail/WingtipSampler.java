package dev.fallingcloud.dynamicelytra.trail;

import dev.fallingcloud.dynamicelytra.DynamicElytraConfig;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Derives the two wingtip world-points plus a smoothed emission intensity each tick.
 *
 * <p>While actually gliding, the tips are computed by replicating the game's own elytra transform chain —
 * {@code PlayerRenderer.setupRotations} (body yaw, the fall-flying pitch toward −90°, the spiral bank roll),
 * the renderer's {@code scale(-1,-1,1)} / {@code translate(0,-1.501,0)} flip, {@code ElytraLayer}'s
 * {@code translate(0,0,0.125)} back-offset, and finally each wing {@code ModelPart}'s pivot + ZYX rotation
 * with the animated wing angles from {@code ElytraModel.setupAnim} (smoothed here with the same EMA the model
 * uses). The emission point is the wing's outer trailing-edge corner, so the trails genuinely follow the
 * rendered elytra as the wings spread, pitch and bank — no mixin needed, just the same math.
 *
 * <p>Riptide spins and fast falls (no spread elytra to follow) keep the simpler body-frame approximation.
 */
public final class WingtipSampler {

    // ElytraModel geometry (pixels): wings pivot at (±5, 0, 0); each wing is a 10x20x2 box with +1
    // deformation, so the plate spans local x∈[-11,1], y∈[-1,21]. When the wings spread (zRot → -90°) the
    // 20px y-axis becomes the SPAN, so y sets how far out the emission point sits.
    private static final float PIVOT_X = 5.0f / 16.0f;
    private static final float TIP_X = 10.0f / 16.0f;
    /** Span position: pulled 2px inside the outer edge (21) so the line starts ON the wing, not off its tip. */
    private static final float TIP_Y = 19.0f / 16.0f;
    /**
     * Chord position: just BEHIND the wing plate's back face (the box spans z ∈ [-1, 3]).
     *
     * <p>This must sit outside the plate. At the old z=1 — dead centre of its 4px thickness — the trail's head
     * was buried inside the elytra geometry, so its fragments fought the wing's own depth and flickered in and
     * out as the wing animated (a classic z-fight). Emitting from behind the trailing edge clears the model
     * entirely and is where a real wing sheds vapor anyway.
     */
    private static final float TIP_Z = 5.0f / 16.0f;

    private WingtipSampler() {}

    public static void sample(Entity entity, DynamicElytraConfig cfg, EntityTrails trails, long now) {
        Vec3 anchor = entity.position().add(0.0, entity.getBbHeight() * 0.35, 0.0);

        // Velocity from consecutive samples (reliable for remote entities); body rotation as fallback.
        Vec3 step = trails.lastShoulder != null ? anchor.subtract(trails.lastShoulder) : Vec3.ZERO;
        float airspeed = (float) (step.length() * 20.0);
        Vec3 forward;
        if (step.lengthSqr() > 1.0e-4) {
            forward = step.normalize();
        } else {
            float yaw = entity instanceof LivingEntity le ? le.yBodyRot : entity.getYRot();
            forward = Vec3.directionFromRotation(entity.getXRot(), yaw).normalize();
        }

        float turnG = 0f;
        if (trails.lastDir != null) {
            turnG = (float) Math.max(0.0, 1.0 - forward.dot(trails.lastDir));
        }

        // Smoothstep (not linear) from the threshold up: a real contrail only forms at speed, so the ramp is
        // flat-zero at the threshold and only reaches full a good way above it, instead of the old linear
        // curve that painted a faint trail the moment you crept past the threshold.
        float speedFactor = smoothstep(cfg.airspeedThreshold(), cfg.airspeedThreshold() + 16.0f, airspeed);
        float turnBoost = clamp(turnG * 6.0f * cfg.turnResponse(), 0.0f, 1.5f);
        float target = clamp(speedFactor * (0.45f + turnBoost), 0.0f, 1.0f);
        trails.smoothedIntensity += (target - trails.smoothedIntensity) * 0.35f; // EMA
        float intensity = trails.smoothedIntensity;
        float width = cfg.width() * (0.5f + intensity);

        Vec3 tipLeft, tipRight;
        if (entity instanceof LivingEntity le && le.isFallFlying() && !le.isAutoSpinAttack()) {
            Vec3[] tips = modelWingtips(le, trails);
            tipLeft = tips[0];
            tipRight = tips[1];
        } else {
            // Riptide / fast-fall: simple body-frame approximation (no spread wings to follow).
            Vec3 up = new Vec3(0, 1, 0);
            Vec3 right = forward.cross(up);
            right = right.lengthSqr() < 1.0e-4 ? new Vec3(1, 0, 0) : right.normalize();
            Vec3 root = anchor.subtract(forward.scale(0.2)).add(forward.scale(-0.35));
            tipLeft = root.subtract(right.scale(0.95));  // entity's left side
            tipRight = root.add(right.scale(0.95));
        }

        trails.left.push(new TrailPoint(tipLeft, now, intensity, width));
        trails.right.push(new TrailPoint(tipRight, now, intensity, width));
        trails.lastShoulder = anchor;
        trails.lastDir = forward;
        trails.lastSeenTick = now;
    }

    /**
     * The exact wingtip world positions, via the same transform chain the renderer applies. Returns
     * {left tip, right tip} (model +X side = the entity's left).
     */
    private static Vec3[] modelWingtips(LivingEntity le, EntityTrails trails) {
        // --- animated wing angles (ElytraModel.setupAnim, fall-flying branch) + its 0.1/frame EMA,
        // --- approximated at tick rate with a faster factor.
        float f4 = 1.0f;
        Vec3 vel = le.getDeltaMovement();
        if (vel.y < 0.0 && vel.lengthSqr() > 1.0e-8) {
            Vec3 vn = vel.normalize();
            f4 = 1.0f - (float) Math.pow(-vn.y, 1.5);
        }
        float targetX = f4 * (float) (Math.PI / 9) + (1.0f - f4) * (float) (Math.PI / 12);
        float targetZ = f4 * (float) (-Math.PI / 2) + (1.0f - f4) * (float) (-Math.PI / 12);
        trails.elytraX += (targetX - trails.elytraX) * 0.3f;
        trails.elytraZ += (targetZ - trails.elytraZ) * 0.3f;

        // --- body transform (PlayerRenderer.setupRotations, fall-flying branch, at tick time) ---
        Matrix4f m = new Matrix4f();
        m.rotateY((float) Math.toRadians(180.0f - le.yBodyRot));

        float f2 = (float) le.getFallFlyingTicks();
        float f3 = Mth.clamp(f2 * f2 / 100.0f, 0.0f, 1.0f);
        m.rotateX((float) Math.toRadians(f3 * (-90.0f - le.getXRot())));

        Vec3 view = le.getViewVector(1.0f);
        Vec3 move = le.getDeltaMovement();
        double d0 = move.horizontalDistanceSqr();
        double d1 = view.horizontalDistanceSqr();
        if (d0 > 0.0 && d1 > 0.0) {
            double d2 = (move.x * view.x + move.z * view.z) / Math.sqrt(d0 * d1);
            double d3 = move.x * view.z - move.z * view.x;
            m.rotateY((float) (Math.signum(d3) * Math.acos(Mth.clamp(d2, -1.0, 1.0))));
        }

        // --- renderer flip + elytra layer back-offset ---
        m.scale(-1.0f, -1.0f, 1.0f);
        m.translate(0.0f, -1.501f, 0.0f);
        m.translate(0.0f, 0.0f, 0.125f);

        // --- per-wing pivot + ZYX rotation (ModelPart.translateAndRotate), tip = outer trailing corner ---
        Vector3f l = new Matrix4f(m)
                .translate(PIVOT_X, 0.0f, 0.0f)
                .rotateZYX(trails.elytraZ, 0.0f, trails.elytraX)
                .transformPosition(new Vector3f(-TIP_X, TIP_Y, TIP_Z));
        Vector3f r = new Matrix4f(m)
                .translate(-PIVOT_X, 0.0f, 0.0f)
                .rotateZYX(-trails.elytraZ, 0.0f, trails.elytraX)
                .transformPosition(new Vector3f(TIP_X, TIP_Y, TIP_Z));

        Vec3 base = le.position();
        return new Vec3[]{
                base.add(l.x, l.y, l.z),
                base.add(r.x, r.y, r.z),
        };
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    /** 0 below {@code edge0}, 1 above {@code edge1}, smooth (3t²−2t³) in between. */
    private static float smoothstep(float edge0, float edge1, float v) {
        if (edge1 <= edge0) return v >= edge1 ? 1.0f : 0.0f;
        float t = clamp((v - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }
}
