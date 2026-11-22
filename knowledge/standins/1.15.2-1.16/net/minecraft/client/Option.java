package mmt.standin.net.minecraft.client;

/**
 * 1.15.2's Option.getCaption() on 1.16.
 *
 * Read from both releases: 1.15.2's getCaption() returned the option's caption translated, followed by ": ". 1.16's
 * createCaption() returns the same as a component (TranslatableComponent of the caption id, then ": "). This returns
 * its text.
 *
 * Fabric's 1.16 post (https://fabricmc.net/2020/06/23/116.html) does not mention it; this is from the game's code alone.
 */
public final class Option {

    private Option() {}

    public static String getCaption(net.minecraft.client.Option self) {
        return self.createCaption().getString();
    }
}
