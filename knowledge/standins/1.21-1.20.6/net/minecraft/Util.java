package mmt.standin.net.minecraft;

/**
 * 1.21's Util.parseAndValidateUntrustedUri on 1.20.6, for a port back, as 1.21 writes it: the URI parsed, refused
 * (URISyntaxException) without a scheme or with one other than http or https (ALLOWED_UNTRUSTED_LINK_PROTOCOLS).
 * 1.20.6 has no such check; what it needs (java.net.URI) is in both.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`net.minecraft.Util#parseAndValidateUntrustedUri` - Returns a URI after validating that the protocol scheme is supported".
 */
public final class Util {

    private Util() {}

    public static java.net.URI parseAndValidateUntrustedUri(String text) throws java.net.URISyntaxException {
        java.net.URI uri = new java.net.URI(text);
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new java.net.URISyntaxException(text, "Missing protocol in URI: " + text);
        }
        String lower = scheme.toLowerCase(java.util.Locale.ROOT);
        if (!lower.equals("http") && !lower.equals("https")) {
            throw new java.net.URISyntaxException(text, "Unsupported protocol in URI: " + text);
        }
        return uri;
    }
}
