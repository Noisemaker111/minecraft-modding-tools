package mmt.standin.net.neoforged.bus.api;

/**
 * NeoForge's event bus 7's Event.hasResult() on bus 8 (NeoForge 20.5 -> 20.6).
 *
 * Read from both buses' jars (net.neoforged:bus 7.2.0 and 8.0.1): bus 7's hasResult() asked whether the event's class
 * was marked @HasResult, which let setResult and getResult use the bus's Event.Result. Bus 8 dropped the generic result
 * altogether (Event.Result, getResult, setResult and hasResult are gone; events that need an outcome declare their own),
 * so no event has the bus's result any more, and the answer is false for every event.
 *
 * NeoForge's 1.20.5 -> 1.20.6 primer does not cover the bus; this is from the bus jars alone.
 */
public final class Event {

    private Event() {}

    public static boolean hasResult(net.neoforged.bus.api.Event self) {
        return false;
    }
}
