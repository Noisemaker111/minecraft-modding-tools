package mmt.standin.net.neoforged.fml.config;

/**
 * FML 4.0's ConfigTracker.registerConfig and acceptSyncedConfig (NeoForge 21.0, FML 4.0.23) on FML 3.0 (NeoForge 20.6,
 * FML 3.0.45), for a port back.
 *
 * Read from both loaders' jars: 4.0's registerConfig(type, spec, container[, fileName]) makes the ModConfig, tracks it
 * (and loads a STARTUP one at once) and lists it under the mod; 3.0's ModConfig constructor tracks itself
 * (ConfigTracker.trackConfig) and ModContainer.addConfig lists it under the mod, which is what 3.0's
 * ModContainer.registerConfig does, with the default file name from the two-argument constructor. 4.0's static
 * acceptSyncedConfig(config, bytes) is 3.0's config.acceptSyncedConfig(bytes): both parse the TOML and post the
 * reloading event. NeoForge's primers cover the game, not the loader; this follows the two loaders' own code.
 */
public final class ConfigTracker {

    private ConfigTracker() {}

    public static net.neoforged.fml.config.ModConfig registerConfig(net.neoforged.fml.config.ConfigTracker self, net.neoforged.fml.config.ModConfig.Type type, net.neoforged.fml.config.IConfigSpec spec, net.neoforged.fml.ModContainer container, String fileName) {
        net.neoforged.fml.config.ModConfig config = new net.neoforged.fml.config.ModConfig(type, spec, container, fileName);
        container.addConfig(config);
        return config;
    }

    public static net.neoforged.fml.config.ModConfig registerConfig(net.neoforged.fml.config.ConfigTracker self, net.neoforged.fml.config.ModConfig.Type type, net.neoforged.fml.config.IConfigSpec spec, net.neoforged.fml.ModContainer container) {
        net.neoforged.fml.config.ModConfig config = new net.neoforged.fml.config.ModConfig(type, spec, container);
        container.addConfig(config);
        return config;
    }

    public static void acceptSyncedConfig(net.neoforged.fml.config.ModConfig config, byte[] bytes) {
        config.acceptSyncedConfig(bytes);
    }
}
