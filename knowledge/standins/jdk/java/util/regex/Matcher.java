package mmt.standin.java.util.regex;

import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.stream.StreamSupport;

/**
 * java.util.regex.Matcher members Java 8 lacks (all Java 9), from the JDK's documented contract:
 * - appendReplacement(StringBuilder, String) and appendTail(StringBuilder): as the StringBuffer forms, the same matcher
 *   state moved on.
 * - replaceAll(Function) and replaceFirst(Function): the matcher reset, each match (the first) replaced by the function's
 *   result for it read as a replacement string ($1 a group, \ an escape), the rest of the input kept.
 * - results(): a stream of the match results find() gives from where the matcher is, without a reset.
 */
public final class Matcher {

    private Matcher() {}

    public static java.util.regex.Matcher appendReplacement(java.util.regex.Matcher self, StringBuilder sb, java.lang.String replacement) {
        StringBuffer part = new StringBuffer();
        self.appendReplacement(part, replacement);
        sb.append(part);
        return self;
    }

    public static StringBuilder appendTail(java.util.regex.Matcher self, StringBuilder sb) {
        StringBuffer part = new StringBuffer();
        self.appendTail(part);
        return sb.append(part);
    }

    public static java.lang.String replaceAll(java.util.regex.Matcher self, Function<MatchResult, java.lang.String> replacer) {
        Objects.requireNonNull(replacer);
        self.reset();
        StringBuffer sb = new StringBuffer();
        while (self.find()) {
            self.appendReplacement(sb, replacer.apply(self));
        }
        self.appendTail(sb);
        return sb.toString();
    }

    public static java.lang.String replaceFirst(java.util.regex.Matcher self, Function<MatchResult, java.lang.String> replacer) {
        Objects.requireNonNull(replacer);
        self.reset();
        StringBuffer sb = new StringBuffer();
        if (self.find()) {
            self.appendReplacement(sb, replacer.apply(self));
        }
        self.appendTail(sb);
        return sb.toString();
    }

    public static java.util.stream.Stream<MatchResult> results(java.util.regex.Matcher self) {
        return StreamSupport.stream(new Spliterators.AbstractSpliterator<MatchResult>(Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL) {
            @Override
            public boolean tryAdvance(Consumer<? super MatchResult> action) {
                if (!self.find()) {
                    return false;
                }
                action.accept(self.toMatchResult());
                return true;
            }
        }, false);
    }
}
