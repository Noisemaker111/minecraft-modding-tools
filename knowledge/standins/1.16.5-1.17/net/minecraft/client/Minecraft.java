package mmt.standin.net.minecraft.client;

import net.minecraft.CrashReport;
import net.minecraft.client.Options;
import net.minecraft.client.resources.language.LanguageManager;

/**
 * 1.16.5's static Minecraft.fillReport(LanguageManager, String, Options, CrashReport) on 1.17.
 *
 * Read from both releases: 1.16.5's fillReport added the client's system details (launched version, graphics backend,
 * resource packs, language, options) to a crash report. 1.17's static fillReport takes the Minecraft first (@Nullable;
 * with it, the window size and more are added) and fills the same details through fillSystemReport. This passes no
 * Minecraft, as 1.16.5 had none to read.
 *
 * Fabric's 1.17 post for developers (https://fabricmc.net/2021/05/27/117-for-developers.html) covers Java 16, Fabric API and
 * Yarn, not this; this is from the game's code alone.
 */
public final class Minecraft {

    private Minecraft() {}

    public static void fillReport(LanguageManager languages, String version, Options options, CrashReport report) {
        net.minecraft.client.Minecraft.fillReport(null, languages, version, options, report);
    }
}
