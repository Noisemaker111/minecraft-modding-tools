package mmt.standin.net.minecraft.network.chat;

import net.minecraft.Util;

/**
 * 1.21.4's ClickEvent on 1.21.5.
 *
 * Read from both releases: 1.21.4's new ClickEvent(action, value) kept the value as a string and read it when clicked:
 * Screen.handleComponentClicked opened OPEN_URL through Util.parseAndValidateUntrustedUri (logging and doing nothing
 * when it failed), OPEN_FILE as a path, RUN_COMMAND and SUGGEST_COMMAND as a command, COPY_TO_CLIPBOARD as text, and
 * BookViewScreen turned CHANGE_PAGE into Integer.parseInt(value) - 1 (nothing when it failed). 1.21.5's ClickEvent is an
 * interface with one record per action holding the parsed value (OpenUrl(URI), OpenFile(String), RunCommand(String),
 * SuggestCommand(String), ChangePage(int), whose page BookViewScreen still lowers by one, CopyToClipboard(String)). So the
 * value is parsed here as the click would have; one the click could not use is no click event (Style takes null), which
 * does nothing when clicked, as before.
 *
 * Agrees with NeoForge's 1.21.4 -> 1.21.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.5/index.md, CC BY 4.0): "ClickEvent is now an interface".
 */
public final class ClickEvent {

    private ClickEvent() {}

    public static net.minecraft.network.chat.ClickEvent init(net.minecraft.network.chat.ClickEvent.Action action, String value) {
        if (action == net.minecraft.network.chat.ClickEvent.Action.OPEN_URL) {
            try {
                return new net.minecraft.network.chat.ClickEvent.OpenUrl(Util.parseAndValidateUntrustedUri(value));
            } catch (Exception e) {
                return null; // URISyntaxException, which the click logged and ignored
            }
        }
        if (action == net.minecraft.network.chat.ClickEvent.Action.OPEN_FILE) {
            return new net.minecraft.network.chat.ClickEvent.OpenFile(value);
        }
        if (action == net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND) {
            return new net.minecraft.network.chat.ClickEvent.RunCommand(value);
        }
        if (action == net.minecraft.network.chat.ClickEvent.Action.SUGGEST_COMMAND) {
            return new net.minecraft.network.chat.ClickEvent.SuggestCommand(value);
        }
        if (action == net.minecraft.network.chat.ClickEvent.Action.CHANGE_PAGE) {
            try {
                return new net.minecraft.network.chat.ClickEvent.ChangePage(Integer.parseInt(value));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (action == net.minecraft.network.chat.ClickEvent.Action.COPY_TO_CLIPBOARD) {
            return new net.minecraft.network.chat.ClickEvent.CopyToClipboard(value);
        }
        throw new IllegalArgumentException("Unknown click action " + action);
    }
}
