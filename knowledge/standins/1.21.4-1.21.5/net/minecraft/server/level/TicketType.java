package mmt.standin.net.minecraft.server.level;

import java.util.Comparator;

/**
 * 1.21.4's TicketType on 1.21.5.
 *
 * Read from both releases: 1.21.4's TicketType.create(name, comparator[, timeout]) made a ticket type named for debugging,
 * whose tickets kept a chunk loaded and ticking until removed or timed out (0 meaning never), told apart by identity, and
 * never saved (forced chunks were saved on their own). 1.21.5's TicketType is a record (timeout, persist, use) registered
 * in BuiltInRegistries.TICKET_TYPE; TicketStorage tells tickets apart by the type's identity (isTicketSameTypeAndLevel),
 * saves only persist types, and names an unregistered type "[unregistered]" (Util.getRegisteredName). So the old type is a
 * new unregistered record with the timeout, not persisted, loading and simulating.
 *
 * Agrees with NeoForge's 1.21.4 -> 1.21.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.21.5/index.md, CC BY 4.0): "TicketType is now a record and no longer has a generic", and its ticket section: a type holds how many ticks the
 * ticket lasts (0 when permanent), whether it is saved, and what it is used for.
 */
public final class TicketType {

    private TicketType() {}

    public static net.minecraft.server.level.TicketType create(String name, Comparator comparator) {
        return new net.minecraft.server.level.TicketType(0L, false, net.minecraft.server.level.TicketType.TicketUse.LOADING_AND_SIMULATION);
    }

    public static net.minecraft.server.level.TicketType create(String name, Comparator comparator, int timeout) {
        return new net.minecraft.server.level.TicketType(timeout, false, net.minecraft.server.level.TicketType.TicketUse.LOADING_AND_SIMULATION);
    }
}
