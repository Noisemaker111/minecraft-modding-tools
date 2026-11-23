import java.util.*;
import java.util.regex.*;

/** Calls a rule gives new arguments. */
final class CallArgumentMigration {

    static String callArguments(String text, Map<String, Object> apply) {
        Pattern statement = Pattern.compile(Json.text(apply, "statement", ""));
        // A method call (.name( ) or a constructor call (new Name( ); "rename" gives the call a new name, "argument" (from 0)
        // rewrites that argument alone.
        Pattern call = Pattern.compile("(\\.|\\bnew\\s+)(" + String.join("|", ProjectModel.strings(apply.get("calls"))) + ")(\\s*\\()");
        String skip = Json.text(apply, "skip", ""), wrap = Json.text(apply, "wrap", "$0"), rename = Json.text(apply, "rename", "");
        int only = apply.get("argument") instanceof Number n ? n.intValue() : -1;
        StringBuilder out = new StringBuilder();
        int done = 0;
        Matcher start = statement.matcher(text);
        while (start.find(done)) {
            int end = JavaText.statementEnd(text, start.start());
            if (end < 0) break;
            out.append(text, done, start.start());
            String body = text.substring(start.start(), end);
            StringBuilder rewritten = new StringBuilder();
            int at = 0;
            Matcher c = call.matcher(body);
            while (c.find(at)) {
                int close = JavaText.closingParen(body, c.end() - 1);
                if (close < 0) break;
                rewritten.append(body, at, c.start()).append(c.group(1)).append(rename.isEmpty() ? c.group(2) : rename).append(c.group(3));
                List<String> parts = JavaText.topLevelArguments(body.substring(c.end(), close));
                for (int i = 0; i < parts.size(); i++) {
                    String part = parts.get(i), arg = part.strip();
                    boolean keep = arg.isEmpty() || (only >= 0 && i != only) || (!skip.isEmpty() && Pattern.compile(skip).matcher(arg).find());
                    String lead = part.substring(0, part.indexOf(arg.isEmpty() ? part : arg)), tail = part.substring(lead.length() + arg.length());
                    rewritten.append(lead).append(keep ? arg : wrap.replace("$0", arg)).append(tail);
                    if (i < parts.size() - 1) rewritten.append(',');
                }
                rewritten.append(')');
                at = close + 1;
            }
            rewritten.append(body.substring(at));
            out.append(rewritten);
            done = end;
        }
        return out.append(text.substring(done)).toString();
    }
}
