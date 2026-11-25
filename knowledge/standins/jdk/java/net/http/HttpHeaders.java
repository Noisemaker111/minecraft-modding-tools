package mmt.standin.java.net.http;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * Java 11's java.net.http.HttpHeaders, for a release on Java 8, from the JDK's documented contract: "a read-only view of
 * a set of HTTP headers", names compared "case-insensitively"; firstValue(name) is the first value of the header,
 * allValues(name) all of them (empty when there are none), firstValueAsLong(name) the first parsed as a long
 * ("NumberFormatException if a value is found, but does not parse as a Long").
 */
public final class HttpHeaders {

    private final Map<String, List<String>> map;

    HttpHeaders(Map<String, List<String>> headers) {
        TreeMap<String, List<String>> copy = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            copy.put(e.getKey(), Collections.unmodifiableList(e.getValue()));
        }
        map = Collections.unmodifiableMap(copy);
    }

    public Optional<String> firstValue(String name) {
        List<String> values = allValues(name);
        return values.isEmpty() ? Optional.<String>empty() : Optional.of(values.get(0));
    }

    public OptionalLong firstValueAsLong(String name) {
        Optional<String> v = firstValue(name);
        return v.isPresent() ? OptionalLong.of(Long.parseLong(v.get().trim())) : OptionalLong.empty();
    }

    public List<String> allValues(String name) {
        List<String> values = map.get(name);
        return values == null ? Collections.<String>emptyList() : values;
    }

    public Map<String, List<String>> map() {
        return map;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HttpHeaders && map.equals(((HttpHeaders) o).map);
    }

    @Override
    public int hashCode() {
        return map.hashCode();
    }

    @Override
    public String toString() {
        return map.toString();
    }
}
