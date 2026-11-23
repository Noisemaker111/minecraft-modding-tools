package mmt.standin.java.time;

import java.time.Instant;
import java.time.ZoneId;

/** Java 9's LocalDate.ofInstant(instant, zone): the date at that instant in that zone, instant.atZone(zone).toLocalDate(). */
public final class LocalDate {

    private LocalDate() {}

    public static java.time.LocalDate ofInstant(Instant instant, ZoneId zone) {
        return instant.atZone(zone).toLocalDate();
    }
}
