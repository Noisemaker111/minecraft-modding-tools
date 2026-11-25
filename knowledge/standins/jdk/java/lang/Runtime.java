package mmt.standin.java.lang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java 9's Runtime.version() and Runtime.Version, from the JDK's documented contract: "the version of the Java Runtime
 * Environment", a version string $VNUM(-$PRE)?(\+$BUILD)?(-$OPT)? parsed into its numbers (feature, interim, update,
 * patch), pre-release, build and optional parts. Java 8 names itself in the older scheme (java.runtime.version
 * "1.8.0_512-b07"), which reads as feature 8, interim 0, update 512, build 7, as JEP 223 maps it; a newer JVM running the
 * release names itself in the new scheme and is read as it is.
 */
public final class Runtime {

    private Runtime() {}

    private static Version current;

    public static synchronized Version version() {
        if (current == null) {
            java.lang.String v = java.lang.System.getProperty("java.runtime.version", java.lang.System.getProperty("java.version"));
            Matcher old = Pattern.compile("1\\.(\\d+)\\.\\d+(?:_(\\d+))?(?:-(?!b\\d)([a-zA-Z0-9]+))?(?:-b(\\d+))?.*").matcher(v);
            int update = old.matches() && old.group(2) != null ? java.lang.Integer.parseInt(old.group(2)) : 0;
            current = old.matches()
                ? Version.parse(old.group(1) + (update > 0 ? ".0." + update : "") + (old.group(3) == null ? "" : "-" + old.group(3))
                    + (old.group(4) == null ? "" : "+" + java.lang.Integer.parseInt(old.group(4))))
                : lenient(v);
        }
        return current;
    }

    /** A version string as a JVM gives it, its leading numbers alone when a vendor added what the scheme does not allow. */
    static Version lenient(java.lang.String v) {
        try {
            return Version.parse(v);
        } catch (java.lang.IllegalArgumentException vendor) {
            Matcher nums = Pattern.compile("[1-9][0-9]*(?:\\.[0-9]+)*").matcher(v);
            return Version.parse(nums.lookingAt() ? nums.group().replaceAll("(\\.0)+$", "") : "8");
        }
    }

    public static final class Version implements java.lang.Comparable<Version> {

        private static final Pattern FORM = Pattern.compile(
            "(?<vnum>[1-9][0-9]*(?:(?:\\.0)*\\.[1-9][0-9]*)*)(?:-(?<pre>[a-zA-Z0-9]+))?(?:(?<plus>\\+)(?<build>0|[1-9][0-9]*)?)?(?:-(?<opt>[-a-zA-Z0-9.]+))?");

        private final List<java.lang.Integer> version;
        private final Optional<java.lang.String> pre;
        private final Optional<java.lang.Integer> build;
        private final Optional<java.lang.String> optional;

        private Version(List<java.lang.Integer> version, Optional<java.lang.String> pre, Optional<java.lang.Integer> build, Optional<java.lang.String> optional) {
            this.version = version;
            this.pre = pre;
            this.build = build;
            this.optional = optional;
        }

        public static Version parse(java.lang.String s) {
            Objects.requireNonNull(s);
            Matcher m = FORM.matcher(s);
            if (!m.matches()) {
                throw new java.lang.IllegalArgumentException("Invalid version string: '" + s + "'");
            }
            List<java.lang.Integer> nums = new ArrayList<>();
            for (java.lang.String part : m.group("vnum").split("\\.")) {
                nums.add(java.lang.Integer.parseInt(part));
            }
            java.lang.String build = m.group("build");
            java.lang.String opt = m.group("opt");
            if (m.group("plus") == null && build == null && opt != null && m.group("pre") != null) {
                // "9-ea-opt" without "+" is not a version: the JDK refuses an optional part after a pre-release with no build
                throw new java.lang.IllegalArgumentException("'+' not found with pre-release and optional components:'" + s + "'");
            }
            if (m.group("plus") != null && build == null && opt == null) {
                throw new java.lang.IllegalArgumentException("'+' found with no trailing build or optional components:'" + s + "'");
            }
            return new Version(Collections.unmodifiableList(nums), Optional.ofNullable(m.group("pre")),
                build == null ? Optional.empty() : Optional.of(java.lang.Integer.parseInt(build)), Optional.ofNullable(opt));
        }

        private int at(int i) {
            return i < version.size() ? version.get(i) : 0;
        }

        public int feature() {
            return at(0);
        }

        public int interim() {
            return at(1);
        }

        public int update() {
            return at(2);
        }

        public int patch() {
            return at(3);
        }

        public int major() {
            return feature();
        }

        public int minor() {
            return interim();
        }

        public int security() {
            return update();
        }

        public List<java.lang.Integer> version() {
            return version;
        }

        public Optional<java.lang.String> pre() {
            return pre;
        }

        public Optional<java.lang.Integer> build() {
            return build;
        }

        public Optional<java.lang.String> optional() {
            return optional;
        }

        @Override
        public int compareTo(Version obj) {
            return compare(obj, false);
        }

        public int compareToIgnoreOptional(Version obj) {
            return compare(obj, true);
        }

        private int compare(Version obj, boolean ignoreOpt) {
            Objects.requireNonNull(obj);
            int size = java.lang.Math.max(version.size(), obj.version.size());
            for (int i = 0; i < size; i++) {
                int c = java.lang.Integer.compare(at(i), obj.at(i));
                if (c != 0) {
                    return c;
                }
            }
            if (pre.isPresent() != obj.pre.isPresent()) {
                return pre.isPresent() ? -1 : 1;
            }
            if (pre.isPresent()) {
                java.lang.String a = pre.get(), b = obj.pre.get();
                boolean an = a.matches("\\d+"), bn = b.matches("\\d+");
                int c = an && bn ? java.lang.Long.compare(java.lang.Long.parseLong(a), java.lang.Long.parseLong(b)) : an ? -1 : bn ? 1 : a.compareTo(b);
                if (c != 0) {
                    return c;
                }
            }
            if (build.isPresent() != obj.build.isPresent()) {
                return build.isPresent() ? 1 : -1;
            }
            if (build.isPresent()) {
                int c = build.get().compareTo(obj.build.get());
                if (c != 0) {
                    return c;
                }
            }
            if (ignoreOpt || optional.isPresent() == obj.optional.isPresent() && !optional.isPresent()) {
                return 0;
            }
            if (optional.isPresent() != obj.optional.isPresent()) {
                return optional.isPresent() ? 1 : -1;
            }
            return optional.get().compareTo(obj.optional.get());
        }

        @Override
        public java.lang.String toString() {
            java.lang.StringBuilder sb = new java.lang.StringBuilder();
            for (int i = 0; i < version.size(); i++) {
                sb.append(i == 0 ? "" : ".").append(version.get(i));
            }
            pre.ifPresent(p -> sb.append('-').append(p));
            if (build.isPresent()) {
                sb.append('+').append(build.get());
                optional.ifPresent(o -> sb.append('-').append(o));
            } else if (optional.isPresent()) {
                sb.append(pre.isPresent() ? "-" : "+-").append(optional.get());
            }
            return sb.toString();
        }

        @Override
        public boolean equals(java.lang.Object obj) {
            return obj instanceof Version && compareTo((Version) obj) == 0;
        }

        public boolean equalsIgnoreOptional(java.lang.Object obj) {
            return obj instanceof Version && compareToIgnoreOptional((Version) obj) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(version, pre, build, optional);
        }
    }
}
