package mmt.standin.java.time;

import java.time.Instant;
import java.time.ZoneId;

/** Java 9's LocalTime.ofInstant(instant, zone): the time of day at that instant in that zone, instant.atZone(zone).toLocalTime(). */
public final class LocalTime {

    private LocalTime() {}

    public static java.time.LocalTime ofInstant(Instant instant, ZoneId zone) {
        return instant.atZone(zone).toLocalTime();
    }
}
