package mmt.standin.net.minecraft.locale;

/**
 * 1.19.4's Language.getOrDefault(key, fallback) on 1.19.3, for a port back.
 *
 * Read from both releases: 1.19.4's languages (ClientLanguage, and the one the server loads from en_us) answer
 * getOrDefault(key, fallback) from their map, the fallback where the key has no entry, and getOrDefault(key) as
 * getOrDefault(key, key). 1.19.3's have getOrDefault(key) (the key where there is no entry) and has(key) on the same map.
 * So it is the entry where has(key), else the fallback.
 */
public final class Language {

    private Language() {
    }

    public static String getOrDefault(net.minecraft.locale.Language self, String key, String fallback) {
        return self.has(key) ? self.getOrDefault(key) : fallback;
    }
}
