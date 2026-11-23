package mmt.standin.net.minecraft.client;

/**
 * 1.21.10's GraphicsStatus, which 1.21.11 removed.
 *
 * Read from both releases: 1.21.10's GraphicsStatus was the enum FAST(0, "options.graphics.fast"), FANCY(1,
 * "options.graphics.fancy"), FABULOUS(2, "options.graphics.fabulous"), an OptionEnum whose toString() was "fast", "fancy"
 * or "fabulous" and byId(id) wrapped around the three. 1.21.11 replaced the option with GraphicsPreset (the same three
 * names and keys plus CUSTOM), a preset that sets many options at once rather than a value read back, so a mod's own use
 * of the old enum (a setting, a label) keeps the old enum as it was.
 *
 * Agrees with NeoForge's 1.21.10 -> 1.21.11 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.11/index.md, CC BY 4.0):
 * "GraphicsStatus -> GraphicsPreset, not one-to-one".
 */
public enum GraphicsStatus implements mmt.standin.net.minecraft.util.OptionEnum {
    FAST(0, "options.graphics.fast"),
    FANCY(1, "options.graphics.fancy"),
    FABULOUS(2, "options.graphics.fabulous");

    private final int id;
    private final String key;

    GraphicsStatus(int id, String key) {
        this.id = id;
        this.key = key;
    }

    @Override
    public int getId() {
        return this.id;
    }

    @Override
    public String getKey() {
        return this.key;
    }

    @Override
    public String toString() {
        return switch (this) {
            case FAST -> "fast";
            case FANCY -> "fancy";
            case FABULOUS -> "fabulous";
        };
    }

    public static GraphicsStatus byId(int id) {
        GraphicsStatus[] all = values();
        return all[Math.floorMod(id, all.length)];
    }
}
