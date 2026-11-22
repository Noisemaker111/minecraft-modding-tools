package mmt.standin.net.minecraft;

import org.slf4j.LoggerFactory;

/**
 * 1.18.1's DefaultUncaughtExceptionHandler(org.apache.logging.log4j.Logger) on 1.18.2.
 *
 * Read from both releases: the handler is the same in both, logging "Caught previously unhandled exception :" with the
 * throwable at error level to the logger it was made with; 1.18.1 took a Log4j Logger, 1.18.2 takes an SLF4J one (1.18.2
 * logs through SLF4J, still written by Log4j underneath). This makes 1.18.2's handler with the SLF4J logger of the same
 * name, which writes to the same Log4j logger.
 *
 * Fabric has no 1.18.2 post of its own (its 1.18 post, https://fabricmc.net/2021/11/30/118.html, covers 1.18.2 and does
 * not mention logging); this is from the game's code alone.
 */
public final class DefaultUncaughtExceptionHandler {

    private DefaultUncaughtExceptionHandler() {}

    public static net.minecraft.DefaultUncaughtExceptionHandler init(org.apache.logging.log4j.Logger logger) {
        return new net.minecraft.DefaultUncaughtExceptionHandler(LoggerFactory.getLogger(logger.getName()));
    }
}
