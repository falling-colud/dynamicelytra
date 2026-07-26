package dev.fallingcloud.dynamicelytra.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.fallingcloud.dynamicelytra.DynamicElytraConfig;
import dev.fallingcloud.dynamicelytra.trail.RibbonHistory;
import dev.fallingcloud.dynamicelytra.trail.TrailPoint;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws each wing as a plane-style contrail in two passes over one Catmull-Rom-splined path:
 *
 * <ul>
 *   <li><b>Stream</b> — a broad, rough-edged translucent wash (long-streak texture with torn edges), tucked
 *       slightly inboard and widening as it ages, like vapor diffusing behind the wing.</li>
 *   <li><b>Tip line</b> — a thin, crisp, near-constant-width line riding exactly on the wingtip path, like the
 *       vortex line off a plane's wingtip.</li>
 * </ul>
 *
 * Rendering goes through vanilla {@link RenderType}s, whose {@code draw()} sets up and — crucially —
 * <b>clears</b> all GL state (blend, cull, depth, shader, texture) around the draw, so nothing leaks into
 * later passes. Hand-managing that state is what corrupts unrelated rendering; letting the RenderType own it
 * keeps things clean.
 */
public final class RibbonRenderer {

    private static final ResourceLocation STREAM_TEX = ResourceLocation.parse("dynamicelytra:textures/stream.png");
    private static final ResourceLocation LINE_TEX = ResourceLocation.parse("dynamicelytra:textures/line.png");
    private static final int SUBDIVISIONS = 8;

    private static RenderType trailType(String name, ResourceLocation tex,
                                        RenderStateShard.WriteMaskStateShard writeMask,
                                        RenderStateShard.DepthTestStateShard depthTest,
                                        java.util.function.Supplier<ShaderInstance> shader) {
        return RenderType.create(
                name,
                DefaultVertexFormat.POSITION_TEX_COLOR,
                VertexFormat.Mode.TRIANGLE_STRIP,
                2048,
                false, false,
                RenderType.CompositeState.builder()
                        .setShaderState(new RenderStateShard.ShaderStateShard(() -> {
                            ShaderInstance sh = shader.get();
                            // Our copies of position_tex_color with a sane alpha cutoff; vanilla is only a
                            // fallback if a shader failed to load (soft edges get chopped, but it renders).
                            return sh != null ? sh : GameRenderer.getPositionTexColorShader();
                        }))
                        .setTextureState(new RenderStateShard.TextureStateShard(tex, false, false))
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setWriteMaskState(writeMask)
                        .setDepthTestState(depthTest)
                        .createCompositeState(false));
    }

    /** The soft wash: colour only. Writing depth would make overlapping translucent strips occlude each other. */
    private static final RenderType TRAIL_STREAM =
            trailType("dynamicelytra:stream", STREAM_TEX, RenderStateShard.COLOR_WRITE,
                    RenderStateShard.LEQUAL_DEPTH_TEST, TrailShaders::trail);
    /** The crisp line, colour pass: soft alpha, no depth (TRAIL_LINE_DEPTH stamps the depth separately). */
    private static final RenderType TRAIL_LINE =
            trailType("dynamicelytra:line", LINE_TEX, RenderStateShard.COLOR_WRITE,
                    RenderStateShard.LEQUAL_DEPTH_TEST, TrailShaders::trail);

    /**
     * The crisp line, depth-only pass.
     *
     * <p>Volumetric cloud mods composite late and depth-test their clouds against the main framebuffer — Simple
     * Clouds' SHADER_SUPPORT pipeline (which Voxy's compat patch force-selects even without Iris) renders clouds
     * at the very end of the level pass, after every render stage. Geometry that only wrote colour is invisible
     * to that test and gets painted over, so writing depth is the only way in — the same contract Voxy uses for
     * its LOD terrain.
     *
     * <p>But depth is binary and the line is translucent, so writing it from the colour pass culled cloud across
     * the line's soft edges and faded tail as well, punching a hole far wider than the visible line (sky where
     * cloud should be). Splitting it out lets the depth stamp use a high alpha cutoff (see vapor_line_depth.fsh):
     * only the opaque core displaces cloud, and that core is exactly what fills the hole.
     */
    private static final RenderType TRAIL_LINE_DEPTH =
            trailType("dynamicelytra:line_depth", LINE_TEX, RenderStateShard.DEPTH_WRITE,
                    RenderStateShard.LEQUAL_DEPTH_TEST, TrailShaders::lineDepth);

    private RibbonRenderer() {}

    private record Sample(Vec3 pos, float intensity, float width, float birth) {}

    /**
     * @param side +1 for the left wing history, -1 for the right — decides which way "inboard" is, so the
     *             rough stream tucks toward the body and the crisp line stays on the outer wing edge.
     */
    public static void draw(RibbonHistory history, float side, Matrix4f pose, Vec3 cam, DynamicElytraConfig cfg,
                            long now, float partialTick) {
        List<TrailPoint> pts = new ArrayList<>();
        for (TrailPoint p : history.points()) pts.add(p);
        if (pts.size() < 2) return;

        // The newest point is the wing's position at the CURRENT tick, but the entity renders lerped between
        // the last two ticks — so the ribbon must stop partway along its final segment, at partialTick, or its
        // head leads the drawn wings by up to a tick of flight.
        //
        // Do that by TRIMMING the spline, never by inserting the interpolated head as a control point. Adding
        // it made the final segment shrink toward zero length while every other segment stayed ~1 block, and
        // Catmull-Rom handles that spacing badly: the curve overshoots and doubles back, reversing the tangent,
        // so the ribbon folded inside-out at the head on any frame with a small partialTick (i.e. constantly,
        // at high framerates). Trimming keeps the control points uniform, so there is no overshoot at all.
        List<Sample> dense = splineTo(pts, partialTick);
        if (dense.size() < 2) return;

        // Smooth intensity along the ribbon: raw per-tick samples fluctuate with every speed/turn wobble,
        // which prints dim patches that make the thin line read as broken pieces.
        float[] smooth = smoothedIntensities(dense);

        int rgb = cfg.color();
        int cr = (rgb >> 16) & 0xFF, cg = (rgb >> 8) & 0xFF, cb = rgb & 0xFF;

        emitStream(dense, smooth, side, pose, cam, cfg, now, partialTick, cr, cg, cb);
        if (cfg.tipLines()) {
            emitLine(dense, smooth, pose, cam, cfg, now, partialTick, cr, cg, cb, TRAIL_LINE);
            // Second pass, depth only: stamps the line's opaque core into the depth buffer so volumetric
            // clouds are displaced by the line instead of painted over it. Same geometry, so it lands exactly
            // on the line drawn above. A MeshData is consumed by its draw, hence the rebuild.
            emitLine(dense, smooth, pose, cam, cfg, now, partialTick, cr, cg, cb, TRAIL_LINE_DEPTH);
        }
    }

    /** Running box-blur (two passes) of the samples' intensities along the ribbon. */
    private static float[] smoothedIntensities(List<Sample> dense) {
        int n = dense.size();
        float[] a = new float[n];
        for (int i = 0; i < n; i++) a[i] = dense.get(i).intensity();
        int radius = SUBDIVISIONS + 3;
        for (int pass = 0; pass < 2; pass++) {
            float[] b = new float[n];
            float sum = 0f;
            int count = 0;
            for (int i = -radius; i <= radius; i++) {
                if (i >= 0 && i < n) { sum += a[i]; count++; }
            }
            for (int i = 0; i < n; i++) {
                b[i] = sum / count;
                int add = i + radius + 1, drop = i - radius;
                if (add < n) { sum += a[add]; count++; }
                if (drop >= 0) { sum -= a[drop]; count--; }
            }
            a = b;
        }
        return a;
    }

    /** The broad rough wash: wider, softer, tucked inboard, widening toward the old end as it diffuses. */
    private static void emitStream(List<Sample> dense, float[] smooth, float side, Matrix4f pose, Vec3 cam,
                                   DynamicElytraConfig cfg, long now, float partialTick, int cr, int cg, int cb) {
        int count = dense.size();
        float densityMax = cfg.density();
        float lifetime = cfg.lifetimeTicks();

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder bb = tess.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_TEX_COLOR);

        // INVARIANT: every path out of this loop must reach bb.build() below. Returning early abandons the
        // Tesselator's shared buffer mid-build — its write offset stays advanced, so the next mesh built from
        // it (the tip line, just below: same format, same strip mode) comes back with these orphaned vertices
        // welded onto its front and draws as a garbage strip smeared across the scene. That is what made the
        // player flicker away for a few frames at a time. Fall back, never return.
        float uLen = 0f;
        Vec3 prevSide = null, prevPerp = null, prevTangent = null;
        for (int i = 0; i < count; i++) {
            Sample s = dense.get(i);
            Vec3 tangent = tangentAt(dense, i);
            if (tangent == null) tangent = prevTangent;         // hairpin: reuse the last good direction
            if (tangent == null) continue;                      // no direction yet; the strip just starts later
            prevTangent = tangent;
            Vec3 sideVec = sideAxis(tangent, s.pos(), cam, prevSide);
            prevSide = sideVec;

            float lengthFrac = i / (float) (count - 1);
            float age = (now + partialTick - s.birth()) / lifetime;
            float ageFade = clamp(1.0f - age, 0.0f, 1.0f);
            // Same speed gate as the line (onset a touch earlier), so the wash doesn't linger as a faint haze
            // at speeds where no trail should form at all.
            float gate = smoothstep(0.12f, 0.38f, smooth[i]);
            float alpha = clamp(ageFade * lengthFrac * gate * densityMax * 0.55f
                    * nearFade(s.pos(), cam), 0.0f, 1.0f);

            // diffuse: broad overall, widest toward the old (tail) end
            float half = s.width() * 1.55f * (0.55f + 0.45f * (1.0f - lengthFrac));

            // Tuck the wash inboard so the crisp line rides the outer edge. tangent × up collapses in a
            // vertical dive, so carry the last good axis forward rather than snapping the offset to zero.
            Vec3 perp = tangent.cross(new Vec3(0, 1, 0));
            if (perp.lengthSqr() < 1.0e-6) {
                perp = prevPerp != null ? prevPerp : Vec3.ZERO;
            } else {
                perp = perp.normalize();
                if (prevPerp != null && perp.dot(prevPerp) < 0) perp = perp.scale(-1);
            }
            if (perp.lengthSqr() > 1.0e-6) prevPerp = perp;
            Vec3 centre = s.pos().add(perp.scale(-side * half * 0.45));

            if (i > 0) uLen += (float) s.pos().distanceTo(dense.get(i - 1).pos());
            float u = uLen * 0.30f - (now + partialTick) * 0.030f; // slow scroll so the vapor drifts

            Vec3 a = centre.add(sideVec.scale(half)).subtract(cam);
            Vec3 b = centre.subtract(sideVec.scale(half)).subtract(cam);
            int ai = (int) (alpha * 255.0f);

            bb.addVertex(pose, (float) a.x, (float) a.y, (float) a.z).setUv(u, 0.0f).setColor(cr, cg, cb, ai);
            bb.addVertex(pose, (float) b.x, (float) b.y, (float) b.z).setUv(u, 1.0f).setColor(cr, cg, cb, ai);
        }

        MeshData mesh = bb.build();
        if (mesh != null) {
            TRAIL_STREAM.draw(mesh); // sets up + clears all render state around the draw
        }
    }

    /** The crisp wingtip line: thin, constant width, brighter, exactly on the wingtip path. */
    private static void emitLine(List<Sample> dense, float[] smooth, Matrix4f pose, Vec3 cam, DynamicElytraConfig cfg,
                                 long now, float partialTick, int cr, int cg, int cb, RenderType type) {
        int count = dense.size();
        float densityMax = cfg.density();
        float lifetime = cfg.lifetimeTicks();

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder bb = tess.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_TEX_COLOR);

        float uLen = 0f;
        Vec3 prevSide = null, prevTangent = null;
        for (int i = 0; i < count; i++) {
            Sample s = dense.get(i);
            // Same invariant as emitStream: reach bb.build() on every path, never abandon the shared buffer.
            Vec3 tangent = tangentAt(dense, i);
            if (tangent == null) tangent = prevTangent;
            if (tangent == null) continue;
            prevTangent = tangent;
            Vec3 sideVec = sideAxis(tangent, s.pos(), cam, prevSide);
            prevSide = sideVec;

            float lengthFrac = i / (float) (count - 1);
            float age = (now + partialTick - s.birth()) / lifetime;
            float ageFade = clamp(1.0f - age, 0.0f, 1.0f);
            // Crisp and continuous: full brightness along the whole line (the tail fades via age alone,
            // plus a short ramp over the oldest quarter), and the intensity influence is flattened
            // (pow 0.6 on an already length-smoothed value) so speed wobbles can't punch dim gaps.
            float tailRamp = clamp(lengthFrac * 4.0f, 0.0f, 1.0f);
            // Gate, don't amplify: pow(smooth, 0.6) lifted a faint 0.1 intensity to a visible 0.25, which is
            // why lines showed at a lazy glide. Smoothstep instead — nothing below the low edge, full above.
            float gate = smoothstep(0.18f, 0.45f, smooth[i]);
            float alpha = clamp((float) Math.pow(ageFade, 0.8) * tailRamp
                    * gate * densityMax * 1.15f * nearFade(s.pos(), cam), 0.0f, 0.95f);

            // constant width — a contrail line doesn't pulse with airspeed
            float half = Math.max(0.028f, cfg.width() * 0.11f);

            if (i > 0) uLen += (float) s.pos().distanceTo(dense.get(i - 1).pos());
            float u = uLen * 0.40f - (now + partialTick) * 0.010f;

            Vec3 a = s.pos().add(sideVec.scale(half)).subtract(cam);
            Vec3 b = s.pos().subtract(sideVec.scale(half)).subtract(cam);
            int ai = (int) (alpha * 255.0f);

            bb.addVertex(pose, (float) a.x, (float) a.y, (float) a.z).setUv(u, 0.0f).setColor(cr, cg, cb, ai);
            bb.addVertex(pose, (float) b.x, (float) b.y, (float) b.z).setUv(u, 1.0f).setColor(cr, cg, cb, ai);
        }

        MeshData mesh = bb.build();
        if (mesh != null) {
            type.draw(mesh);
        }
    }

    /**
     * A stable flight direction at sample {@code i}. Neighbouring samples can coincide (the spline packs
     * SUBDIVISIONS points into a short segment, and the player may barely move in a tick), so instead of
     * blindly differencing the adjacent pair this searches outward for the nearest samples that are actually
     * apart. The old code fell back to a hard-coded "straight up" whenever the pair coincided, which yanked the
     * ribbon's orientation 90° for a frame — the twitch at the wings. Falls back to the ribbon's overall
     * direction.
     *
     * <p>Returns null when no direction can be had here. That is <em>not</em> only the whole-ribbon-is-one-point
     * case: it also fires mid-ribbon on a hairpin, where the nearest distinct samples on either side land on top
     * of each other and cancel. Callers must therefore treat null as "reuse the last good tangent" and carry on
     * to their {@code build()} — see the invariant in {@link #emitStream}.
     */
    private static Vec3 tangentAt(List<Sample> dense, int i) {
        int count = dense.size();
        Vec3 p = dense.get(i).pos();
        Vec3 back = null, fwd = null;
        for (int k = i - 1; k >= 0; k--) {
            if (dense.get(k).pos().distanceToSqr(p) > 1.0e-6) { back = dense.get(k).pos(); break; }
        }
        for (int k = i + 1; k < count; k++) {
            if (dense.get(k).pos().distanceToSqr(p) > 1.0e-6) { fwd = dense.get(k).pos(); break; }
        }
        Vec3 t;
        if (back != null && fwd != null) t = fwd.subtract(back);
        else if (fwd != null) t = fwd.subtract(p);
        else if (back != null) t = p.subtract(back);
        else t = dense.get(count - 1).pos().subtract(dense.get(0).pos()); // whole-ribbon direction
        return t.lengthSqr() < 1.0e-10 ? null : t.normalize();
    }

    /**
     * The camera-facing width axis, made continuous. {@code tangent × toCam} collapses when you look straight
     * down the ribbon (common in first person), and its sign can flip between neighbouring samples — both of
     * which make the wide, soft wash flicker. Falls back to the previous sample's axis and keeps the sign
     * consistent along the strip.
     */
    private static Vec3 sideAxis(Vec3 tangent, Vec3 pos, Vec3 cam, Vec3 prevSide) {
        Vec3 side = tangent.cross(cam.subtract(pos));
        if (side.lengthSqr() < 1.0e-6) {
            side = prevSide != null ? prevSide : tangent.cross(new Vec3(0, 1, 0));
            if (side.lengthSqr() < 1.0e-6) side = tangent.cross(new Vec3(1, 0, 0));
            if (side.lengthSqr() < 1.0e-6) return new Vec3(1, 0, 0);
        }
        side = side.normalize();
        if (prevSide != null && side.dot(prevSide) < 0) side = side.scale(-1); // no 180° flips mid-ribbon
        return side;
    }

    /**
     * Catmull-Rom over the raw history points, stopping at {@code headT} (0..1) through the FINAL segment.
     *
     * <p>Control points stay uniformly spaced, so the curve never overshoots. The head is emitted as an exact
     * sample at {@code headT} rather than snapping to a subdivision, so it glides smoothly; and because whole
     * subdivisions are only emitted below it, the head always lands at least ~1/SUBDIVISIONS of a segment past
     * the previous sample — never close enough for their separation to collapse into float noise.
     */
    private static List<Sample> splineTo(List<TrailPoint> pts, float headT) {
        int m = pts.size();
        List<Sample> out = new ArrayList<>(m * SUBDIVISIONS + 2);
        for (int i = 0; i < m - 1; i++) {
            boolean last = i == m - 2;
            int steps = last ? (int) Math.floor(SUBDIVISIONS * headT) : SUBDIVISIONS;
            for (int j = 0; j < steps; j++) {
                out.add(sampleAt(pts, i, j / (float) SUBDIVISIONS));
            }
            if (last) {
                out.add(sampleAt(pts, i, headT));
            }
        }
        return out;
    }

    /** The curve sample at parameter {@code t} within segment {@code i} (from pts[i] to pts[i+1]). */
    private static Sample sampleAt(List<TrailPoint> pts, int i, float t) {
        int m = pts.size();
        Vec3 p0 = pts.get(Math.max(0, i - 1)).pos();
        Vec3 p1 = pts.get(i).pos();
        Vec3 p2 = pts.get(i + 1).pos();
        Vec3 p3 = pts.get(Math.min(m - 1, i + 2)).pos();
        TrailPoint a = pts.get(i), b = pts.get(i + 1);
        return new Sample(catmull(p0, p1, p2, p3, t),
                lerp(a.intensity(), b.intensity(), t),
                lerp(a.width(), b.width(), t),
                lerp((float) a.birthTick(), (float) b.birthTick(), t));
    }

    private static Vec3 catmull(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, float t) {
        float t2 = t * t, t3 = t2 * t;
        double x = 0.5 * ((2 * p1.x) + (-p0.x + p2.x) * t + (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2 + (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3);
        double y = 0.5 * ((2 * p1.y) + (-p0.y + p2.y) * t + (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2 + (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3);
        double z = 0.5 * ((2 * p1.z) + (-p0.z + p2.z) * t + (2 * p0.z - 5 * p1.z + 4 * p2.z - p3.z) * t2 + (-p0.z + 3 * p1.z - 3 * p2.z + p3.z) * t3);
        return new Vec3(x, y, z);
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    /** 0 below {@code edge0}, 1 above {@code edge1}, smooth (3t²−2t³) in between. */
    private static float smoothstep(float edge0, float edge1, float v) {
        float t = clamp((v - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    /**
     * Fades geometry out as it approaches the camera. This is what lets the local player's trail draw in first
     * person: the wing roots sit barely a block from the eyes, and without this the ribbon's head smears across
     * the lens. It also saves third person, where the trail streams straight through the orbit camera. Distance
     * is measured per-vertex, so a ribbon only dims on the stretch that is actually close.
     */
    private static float nearFade(Vec3 pos, Vec3 cam) {
        return smoothstep(0.85f, 2.6f, (float) pos.distanceTo(cam));
    }
}
