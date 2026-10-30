package org.semoss.h2.migration;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Preserve H2 1.4 text LOB aliases in CREATE TABLE column declarations.
 * State spans SCRIPT lines so multiline literals/comments cannot become DDL.
 * This intentionally does not replace TEXT used as an identifier or expression.
 */
final class LegacyTextTypeRewriter {
    private static final Set<String> LOB_TYPES = Set.of("TEXT", "LONGTEXT", "MEDIUMTEXT", "TINYTEXT", "NTEXT");
    private static final Set<String> TABLE_MODIFIERS = Set.of("CACHED", "MEMORY", "LOCAL", "GLOBAL", "TEMP", "TEMPORARY");
    private static final Set<String> CONSTRAINTS = Set.of("CONSTRAINT", "PRIMARY", "FOREIGN", "UNIQUE", "CHECK");
    private int statement; // 0: start, 1: CREATE, 2: TABLE, -1: other
    private int depth;
    private int column; // 0: column name, 1: type, 2: remainder/constraint
    private char quote;
    private int blockComment;
    private boolean dollarQuote;

    ScriptRewriter.RewriteResult rewrite(String line) {
        StringBuilder out = new StringBuilder();
        Map<String, Integer> counts = new LinkedHashMap<>();
        int replacements = 0;
        for (int i = 0; i < line.length();) {
            char c = line.charAt(i);
            String pair = i + 1 < line.length() ? line.substring(i, i + 2) : "";
            if (quote != 0) {
                out.append(c);
                i++;
                if (c == quote) {
                    if (i < line.length() && line.charAt(i) == quote) out.append(line.charAt(i++));
                    else quote = 0;
                }
            } else if (dollarQuote) {
                if (pair.equals("$$")) { out.append(pair); i += 2; dollarQuote = false; }
                else { out.append(c); i++; }
            } else if (blockComment > 0) {
                if (pair.equals("/*")) { blockComment++; out.append(pair); i += 2; }
                else if (pair.equals("*/")) { blockComment--; out.append(pair); i += 2; }
                else { out.append(c); i++; }
            } else if (pair.equals("--")) {
                out.append(line.substring(i));
                break;
            } else if (pair.equals("/*")) {
                blockComment = 1; out.append(pair); i += 2;
            } else if (pair.equals("$$")) {
                dollarQuote = true; out.append(pair); i += 2;
            } else if (c == '\'' || c == '"') {
                token("", c == '"');
                quote = c; out.append(c); i++;
            } else if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                int start = i++;
                while (i < line.length() && (Character.isLetterOrDigit(line.charAt(i))
                        || line.charAt(i) == '_' || line.charAt(i) == '$')) i++;
                String word = line.substring(start, i);
                String upper = word.toUpperCase(Locale.ROOT);
                if (token(upper, false)) {
                    out.append("CLOB");
                    counts.merge(upper, 1, Integer::sum);
                    replacements++;
                } else out.append(word);
            } else {
                if (!Character.isWhitespace(c)) token(String.valueOf(c), false);
                out.append(c); i++;
            }
        }
        return new ScriptRewriter.RewriteResult(out.toString(), replacements, counts);
    }

    private boolean token(String value, boolean identifier) {
        if (!identifier && value.equals(";")) {
            statement = 0; depth = 0; column = 0;
        } else if (statement == 0) {
            statement = !identifier && value.equals("CREATE") ? 1 : -1;
        } else if (statement == 1) {
            if (!identifier && value.equals("TABLE")) statement = 2;
            else if (identifier || !TABLE_MODIFIERS.contains(value)) statement = -1;
        } else if (statement == 2) {
            if (!identifier && value.equals("(")) {
                depth++;
            } else if (!identifier && value.equals(")")) {
                if (--depth == 0) statement = -1;
            } else if (depth == 0) {
                if (!identifier && value.equals("AS")) statement = -1;
            } else if (depth == 1) {
                if (!identifier && value.equals(",")) column = 0;
                else if (column == 0) column = !identifier && CONSTRAINTS.contains(value) ? 2 : 1;
                else if (column == 1) {
                    column = 2;
                    return !identifier && LOB_TYPES.contains(value);
                }
            }
        }
        return false;
    }
}
