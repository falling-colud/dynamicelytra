package dev.fallingcloud.dynamicelytra;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * User-facing settings for Vapor, backed by a NeoForge {@link ModConfigSpec} and edited from the
 * <b>Mods &rarr; Vapor &rarr; Config</b> screen (NeoForge auto-generates the GUI). Persisted to
 * {@code config/dynamicelytra-client.toml}.
 *
 * <p>Values are read through accessor methods rather than public fields so the backing store stays an
 * implementation detail. If a pre-2.1 {@code config/vapor.json} is present its values seed the defaults, so
 * settings tuned under the old Sodium options page carry over on first launch. That file is only ever read,
 * never written or deleted.
 */
public final class DynamicElytraConfig {
    public static final ModConfigSpec SPEC;

    private static final DynamicElytraConfig INSTANCE = new DynamicElytraConfig();
    /** Settings for the first-person speed-lines overlay. */
    public final SpeedLines speedLines = new SpeedLines();

    // --- trails --------------------------------------------------------------
    private static final ModConfigSpec.BooleanValue ENABLED;
    private static final ModConfigSpec.IntValue LIFETIME;
    private static final ModConfigSpec.IntValue DENSITY;
    private static final ModConfigSpec.IntValue WIDTH;
    private static final ModConfigSpec.BooleanValue TIP_LINES;
    private static final ModConfigSpec.IntValue AIRSPEED;
    private static final ModConfigSpec.IntValue TURN_RESPONSE;
    private static final ModConfigSpec.IntValue HUE;
    private static final ModConfigSpec.IntValue SATURATION;
    private static final ModConfigSpec.BooleanValue SHOW_OTHERS;
    private static final ModConfigSpec.BooleanValue FIRST_PERSON;
    private static final ModConfigSpec.BooleanValue FALL_TRAILS;
    private static final ModConfigSpec.BooleanValue RIPTIDE_TRAILS;

    // --- speed lines ---------------------------------------------------------
    private static final ModConfigSpec.BooleanValue SL_ENABLED;
    private static final ModConfigSpec.EnumValue<Style> SL_STYLE;
    private static final ModConfigSpec.EnumValue<Palette> SL_COLOR;
    private static final ModConfigSpec.IntValue SL_DENSITY;
    private static final ModConfigSpec.IntValue SL_THRESHOLD;
    private static final ModConfigSpec.IntValue SL_OPACITY;
    private static final ModConfigSpec.IntValue SL_LENGTH;
    private static final ModConfigSpec.IntValue SL_FOCUS;
    private static final ModConfigSpec.IntValue SL_SPRINT;
    private static final ModConfigSpec.IntValue SL_FALL;
    private static final ModConfigSpec.IntValue SL_VEHICLE;
    private static final ModConfigSpec.IntValue SL_RIPTIDE;
    private static final ModConfigSpec.IntValue SL_ELYTRA;

    /** Look of the first-person overlay. */
    public enum Style { LINES, SMEAR, VIGNETTE }

    /** Colour presets for the overlay. */
    public enum Palette {
        WHITE(0xFFFFFF), CYAN(0x6FE0FF), GOLD(0xFFD060), CRIMSON(0xFF5A5A), VIOLET(0xC08CFF);

        private final int rgb;
        Palette(int rgb) { this.rgb = rgb; }
        public int rgb() { return rgb; }
    }

    static {
        JsonObject old = readLegacyJson();
        JsonObject oldSl = childObject(old, "speedLines");
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("Jet-style wingtip vapor trails.")
                .translation("dynamicelytra.config.trails").push("trails");
        ENABLED = b.comment("Master switch for vapor trails.")
                .translation("dynamicelytra.config.enabled")
                .define("enabled", bool(old, "enabled", true));
        LIFETIME = b.comment("How long each trail point lingers before it fades out, in ticks.")
                .translation("dynamicelytra.config.lifetime")
                .defineInRange("lifetimeTicks", integer(old, "lifetimeTicks", 45), 6, 200);
        DENSITY = b.comment("Peak opacity of the ribbon, as a percentage.")
                .translation("dynamicelytra.config.density")
                .defineInRange("density", integer(old, "density", 70), 0, 100);
        WIDTH = b.comment("Base ribbon thickness, as a percentage of one block.")
                .translation("dynamicelytra.config.width")
                .defineInRange("width", integer(old, "width", 45), 5, 200);
        TIP_LINES = b.comment("Draw a thin crisp contrail line on each wingtip edge, over the rough vapor wash.")
                .translation("dynamicelytra.config.tip_lines")
                .define("tipLines", bool(old, "tipLines", true));
        AIRSPEED = b.comment("Airspeed (blocks/sec) below which no trail forms.")
                .translation("dynamicelytra.config.airspeed")
                .defineInRange("airspeedThreshold", integer(old, "airspeedThreshold", 18), 0, 60);
        TURN_RESPONSE = b.comment("How strongly a hard bank thickens and brightens the ribbon, as a percentage.")
                .translation("dynamicelytra.config.turn_response")
                .defineInRange("turnResponse", integer(old, "turnResponse", 70), 0, 200);
        HUE = b.comment("Trail colour hue, in degrees.")
                .translation("dynamicelytra.config.hue")
                .defineInRange("hue", integer(old, "hue", 205), 0, 360);
        SATURATION = b.comment("Colour intensity. 0 is pure white vapor.")
                .translation("dynamicelytra.config.saturation")
                .defineInRange("saturation", integer(old, "saturation", 0), 0, 100);
        SHOW_OTHERS = b.comment("Draw trails for other players and mobs, not just you.")
                .translation("dynamicelytra.config.show_others")
                .define("showOthers", bool(old, "showOthers", true));
        FIRST_PERSON = b.comment("Draw your own trail in first person. It fades near the camera so it streams",
                        "away behind you instead of covering the view.")
                .translation("dynamicelytra.config.first_person")
                .define("firstPersonTrails", bool(old, "firstPersonTrails", true));
        FALL_TRAILS = b.comment("Emit a thin stream on a fast non-elytra free fall.")
                .translation("dynamicelytra.config.fall_trails")
                .define("fallTrails", bool(old, "fallTrails", true));
        RIPTIDE_TRAILS = b.comment("Emit a burst on a riptide trident launch.")
                .translation("dynamicelytra.config.riptide_trails")
                .define("riptideTrails", bool(old, "riptideTrails", true));
        b.pop();

        b.comment("First-person speed lines that bloom the faster you move.")
                .translation("dynamicelytra.config.speedlines").push("speed_lines");
        SL_ENABLED = b.comment("Master switch for the first-person speed-lines overlay.")
                .translation("dynamicelytra.config.sl_enabled")
                .define("enabled", bool(oldSl, "enabled", true));
        SL_STYLE = b.comment("LINES = crisp tapered streaks, SMEAR = softer denser wedges,",
                        "VIGNETTE = the screen edges rush and darken.")
                .translation("dynamicelytra.config.sl_style")
                .defineEnum("style", legacyStyle(oldSl));
        SL_COLOR = b.comment("Streak / vignette colour.")
                .translation("dynamicelytra.config.sl_color")
                .defineEnum("color", legacyPalette(oldSl));
        SL_DENSITY = b.comment("How many streaks appear, as a percentage.")
                .translation("dynamicelytra.config.sl_density")
                .defineInRange("density", integer(oldSl, "density", 70), 0, 100);
        SL_THRESHOLD = b.comment("Speed (blocks/sec) at which the effect begins.")
                .translation("dynamicelytra.config.sl_threshold")
                .defineInRange("threshold", integer(oldSl, "threshold", 9), 0, 40);
        SL_OPACITY = b.comment("Peak opacity of the effect, as a percentage.")
                .translation("dynamicelytra.config.sl_opacity")
                .defineInRange("maxOpacity", integer(oldSl, "maxOpacity", 70), 0, 100);
        SL_LENGTH = b.comment("How far the streaks reach toward the edges, as a percentage.")
                .translation("dynamicelytra.config.sl_length")
                .defineInRange("lineLength", integer(oldSl, "lineLength", 70), 20, 100);
        SL_FOCUS = b.comment("How far the focus point shifts toward the bottom of the screen, as a percentage.")
                .translation("dynamicelytra.config.sl_focus")
                .defineInRange("focusOffset", integer(oldSl, "focusOffset", 12), -50, 50);
        SL_SPRINT = b.comment("Effect strength while sprinting. 0 disables this trigger.")
                .translation("dynamicelytra.config.sl_sprint")
                .defineInRange("sprintMultiplier", integer(oldSl, "sprintMultiplier", 60), 0, 200);
        SL_FALL = b.comment("Effect strength while falling fast. 0 disables this trigger.")
                .translation("dynamicelytra.config.sl_fall")
                .defineInRange("fallMultiplier", integer(oldSl, "fallMultiplier", 90), 0, 200);
        SL_VEHICLE = b.comment("Effect strength in boats, minecarts and on horses. 0 disables this trigger.")
                .translation("dynamicelytra.config.sl_vehicle")
                .defineInRange("vehicleMultiplier", integer(oldSl, "vehicleMultiplier", 100), 0, 200);
        SL_RIPTIDE = b.comment("Effect strength on a riptide trident launch. 0 disables this trigger.")
                .translation("dynamicelytra.config.sl_riptide")
                .defineInRange("riptideMultiplier", integer(oldSl, "riptideMultiplier", 120), 0, 200);
        SL_ELYTRA = b.comment("Effect strength while gliding. 0 disables this trigger.")
                .translation("dynamicelytra.config.sl_elytra")
                .defineInRange("elytraMultiplier", integer(oldSl, "elytraMultiplier", 120), 0, 200);
        b.pop();

        SPEC = b.build();
    }

    private DynamicElytraConfig() {}

    public static DynamicElytraConfig get() {
        return INSTANCE;
    }

    // --- trail accessors -----------------------------------------------------
    public boolean enabled()            { return ENABLED.get(); }
    public int lifetimeTicks()          { return LIFETIME.get(); }
    public float density()              { return DENSITY.get() / 100.0f; }
    public float width()                { return WIDTH.get() / 100.0f; }
    public boolean tipLines()           { return TIP_LINES.get(); }
    public float airspeedThreshold()    { return AIRSPEED.get(); }
    public float turnResponse()         { return TURN_RESPONSE.get() / 100.0f; }
    public boolean showOthers()         { return SHOW_OTHERS.get(); }
    public boolean firstPersonTrails()  { return FIRST_PERSON.get(); }
    public boolean fallTrails()         { return FALL_TRAILS.get(); }
    public boolean riptideTrails()      { return RIPTIDE_TRAILS.get(); }

    /** Packed 0xRRGGBB of the configured hue/saturation at full value. */
    public int color() {
        float s = SATURATION.get() / 100.0f;
        float h = (HUE.get() % 360) / 60.0f;
        float c = s;               // value = 1
        float xx = c * (1 - Math.abs(h % 2 - 1));
        float r, g, bb;
        int hi = (int) h;
        switch (hi) {
            case 0 -> { r = c; g = xx; bb = 0; }
            case 1 -> { r = xx; g = c; bb = 0; }
            case 2 -> { r = 0; g = c; bb = xx; }
            case 3 -> { r = 0; g = xx; bb = c; }
            case 4 -> { r = xx; g = 0; bb = c; }
            default -> { r = c; g = 0; bb = xx; }
        }
        float m = 1 - c; // add so value stays 1 (white when saturation 0)
        int ri = Math.round((r + m) * 255);
        int gi = Math.round((g + m) * 255);
        int bi = Math.round((bb + m) * 255);
        return (ri << 16) | (gi << 8) | bi;
    }

    /** Accessors for the first-person speed-lines overlay. */
    public static final class SpeedLines {
        private SpeedLines() {}

        public boolean enabled()     { return SL_ENABLED.get(); }
        public Style style()         { return SL_STYLE.get(); }
        public int color()           { return SL_COLOR.get().rgb(); }
        public float density()       { return SL_DENSITY.get() / 100.0f; }
        public float threshold()     { return SL_THRESHOLD.get(); }
        public float maxOpacity()    { return SL_OPACITY.get() / 100.0f; }
        public float lineLength()    { return SL_LENGTH.get() / 100.0f; }
        public float focusOffset()   { return SL_FOCUS.get() / 100.0f; }
        public float sprintMul()     { return SL_SPRINT.get() / 100.0f; }
        public float fallMul()       { return SL_FALL.get() / 100.0f; }
        public float vehicleMul()    { return SL_VEHICLE.get() / 100.0f; }
        public float riptideMul()    { return SL_RIPTIDE.get() / 100.0f; }
        public float elytraMul()     { return SL_ELYTRA.get() / 100.0f; }
    }

    // --- legacy config/vapor.json seeding ------------------------------------
    private static JsonObject readLegacyJson() {
        try {
            Path path = FMLPaths.CONFIGDIR.get().resolve("vapor.json");
            if (Files.exists(path)) {
                JsonElement parsed = JsonParser.parseString(Files.readString(path));
                if (parsed.isJsonObject()) {
                    DynamicElytra.LOGGER.info("[Dynamic Elytra] Seeding config defaults from legacy config/vapor.json.");
                    return parsed.getAsJsonObject();
                }
            }
        } catch (Exception e) {
            DynamicElytra.LOGGER.warn("[Dynamic Elytra] Could not read legacy config/vapor.json, using built-in defaults", e);
        }
        return null;
    }

    private static JsonObject childObject(JsonObject parent, String key) {
        if (parent != null && parent.has(key) && parent.get(key).isJsonObject()) {
            return parent.getAsJsonObject(key);
        }
        return null;
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        try {
            return o != null && o.has(key) ? o.get(key).getAsBoolean() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static int integer(JsonObject o, String key, int def) {
        try {
            return o != null && o.has(key) ? o.get(key).getAsInt() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static Style legacyStyle(JsonObject o) {
        int i = integer(o, "style", 0);
        Style[] values = Style.values();
        return values[Math.max(0, Math.min(values.length - 1, i))];
    }

    private static Palette legacyPalette(JsonObject o) {
        int i = integer(o, "colorPreset", 0);
        Palette[] values = Palette.values();
        return values[Math.max(0, Math.min(values.length - 1, i))];
    }
}
