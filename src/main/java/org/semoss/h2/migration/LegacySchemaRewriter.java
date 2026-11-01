package org.semoss.h2.migration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tracks CREATE TABLE columns across a H2 1.4 SCRIPT and fixes two shapes 2.x reads wrongly.
 * ENUM: 1.4 dumps 0-based ordinals but 2.x reads ints as 1-based, so each ordinal becomes its label.
 * NULL-typed columns are recorded so ScriptRewriter can strip indexes 2.x refuses to build.
 * An ENUM it cannot map fails the rewrite rather than shift data.
 */
final class LegacySchemaRewriter {
    private static final Set<String> TABLE_MODIFIERS = Set.of("CACHED", "MEMORY", "LOCAL", "GLOBAL", "TEMP", "TEMPORARY");
    private static final Set<String> CONSTRAINTS = Set.of("CONSTRAINT", "PRIMARY", "FOREIGN", "UNIQUE", "CHECK");
    private static final Set<String> NAME_NOISE = Set.of("IF", "NOT", "EXISTS");
    // INSERT forms without a VALUES list; they copy labels, not ordinals
    private static final Set<String> NON_VALUES = Set.of("SELECT", "DIRECT", "SORTED", "DEFAULT", "WITH", "TABLE");

    private enum Stmt { START, CREATE, TABLE, INSERT, ROWS, OTHER }
    private enum Kind { WORD, IDENT, STRING, PUNCT }
    private record Column(String name, boolean nullType, List<String> labels) { }

    private final Map<String, List<Column>> tables = new HashMap<>();
    private int ordinals;

    // lexical state, kept across lines
    private char quote;
    private int blockComment;
    private boolean dollarQuote;
    private final StringBuilder literal = new StringBuilder();

    // statement state
    private Stmt stmt = Stmt.START;
    private int depth;
    private final StringBuilder name = new StringBuilder();
    private boolean nameDone;
    // CREATE TABLE
    private List<Column> columns;
    private int colPos; // 0 name, 1 type, 2 rest, 3 constraint
    private String colName;
    private boolean colNull;
    private List<String> labels;
    private boolean expectLabels;
    private boolean inLabels;
    private boolean expectDefault;
    // INSERT
    private List<String> insertColumns;
    private boolean inColumnList;
    private Map<Integer, List<String>> enumFields;
    private int field;
    private int fieldTokens;
    private int rowWidth;

    int enumOrdinalReplacements() {
        return ordinals;
    }

    boolean isNullTyped(String tableRef, String column) {
        List<Column> cols = tables.get(key(tableRef));
        if (cols == null) return false;
        String col = normalize(column);
        return cols.stream().anyMatch(c -> c.nullType && c.name.equals(col));
    }

    ScriptRewriter.RewriteResult rewrite(String line) throws IOException {
        StringBuilder out = new StringBuilder(line.length());
        int before = ordinals;
        if (quote != 0) literal.append('\n');
        for (int i = 0; i < line.length();) {
            char c = line.charAt(i);
            String pair = i + 1 < line.length() ? line.substring(i, i + 2) : "";
            if (quote != 0) {
                out.append(c);
                i++;
                if (c != quote) { literal.append(c); continue; }
                if (i < line.length() && line.charAt(i) == quote) {
                    literal.append(c); out.append(line.charAt(i++));
                } else {
                    Kind kind = quote == '"' ? Kind.IDENT : Kind.STRING;
                    quote = 0;
                    token(kind, literal.toString());
                }
            } else if (dollarQuote) {
                if (pair.equals("$$")) { out.append(pair); i += 2; dollarQuote = false; }
                else { out.append(c); i++; }
            } else if (blockComment > 0) {
                if (pair.equals("/*")) { blockComment++; out.append(pair); i += 2; }
                else if (pair.equals("*/")) { blockComment--; out.append(pair); i += 2; }
                else { out.append(c); i++; }
            } else if (pair.equals("--")) {
                out.append(line, i, line.length());
                break;
            } else if (pair.equals("/*")) {
                blockComment = 1; out.append(pair); i += 2;
            } else if (pair.equals("$$")) {
                dollarQuote = true; out.append(pair); i += 2;
            } else if (c == '\'' || c == '"') {
                quote = c; literal.setLength(0); out.append(c); i++;
            } else if (isWordChar(c)) {
                int start = i++;
                while (i < line.length() && isWordChar(line.charAt(i))) i++;
                String word = line.substring(start, i);
                String replacement = token(Kind.WORD, word);
                out.append(replacement != null ? replacement : word);
            } else {
                if (!Character.isWhitespace(c)) token(Kind.PUNCT, String.valueOf(c));
                out.append(c); i++;
            }
        }
        int replaced = ordinals - before;
        Map<String, Integer> counts = replaced > 0 ? Map.of("ENUM_ORDINAL", replaced) : Map.of();
        return new ScriptRewriter.RewriteResult(out.toString(), replaced, counts);
    }

    /** Returns replacement text for a WORD token, or null to keep it. */
    private String token(Kind kind, String text) throws IOException {
        String upper = kind == Kind.WORD ? text.toUpperCase(Locale.ROOT) : text;
        if (kind == Kind.PUNCT && text.equals(";")) {
            endStatement();
            return null;
        }
        if (kind == Kind.WORD && upper.equals("ENUM") && !(stmt == Stmt.TABLE && depth == 1 && colPos == 1)) {
            throw new IOException("ENUM outside a CREATE TABLE column type cannot be migrated safely"
                    + " (H2 1.4 dumps 0-based ordinals)");
        }
        switch (stmt) {
            case START -> {
                if (kind == Kind.WORD && upper.equals("CREATE")) stmt = Stmt.CREATE;
                else if (kind == Kind.WORD && upper.equals("INSERT")) { stmt = Stmt.INSERT; startName(); }
                else stmt = Stmt.OTHER;
            }
            case CREATE -> {
                if (kind == Kind.WORD && upper.equals("TABLE")) {
                    stmt = Stmt.TABLE; startName(); columns = new ArrayList<>();
                } else if (kind != Kind.WORD || !TABLE_MODIFIERS.contains(upper)) {
                    stmt = Stmt.OTHER;
                }
            }
            case TABLE -> { return tableToken(kind, upper); }
            case INSERT -> insertToken(kind, upper);
            case ROWS -> { return rowToken(kind, text, upper); }
            default -> { }
        }
        return null;
    }

    /** Returns replacement text for an integer ENUM default, else null. */
    private String tableToken(Kind kind, String text) throws IOException {
        if (depth == 0) {
            if (kind == Kind.PUNCT && text.equals("(")) {
                depth = 1; nameDone = true; colPos = 0;
            } else if (!nameDone && !(kind == Kind.WORD && NAME_NOISE.contains(text) && name.isEmpty())) {
                if (kind == Kind.WORD && text.equals("AS")) stmt = Stmt.OTHER;
                else appendName(kind, text);
            }
            return null;
        }
        if (expectDefault && depth == 1) {
            // ENUM DEFAULT / ON UPDATE: 1.4 keeps a bare integer as a 0-based ordinal
            expectDefault = false;
            if (kind == Kind.WORD && text.chars().allMatch(Character::isDigit)) return label(labels, text, colName);
            if (kind != Kind.STRING && !(kind == Kind.WORD && text.equals("NULL"))) {
                throw new IOException("Unsupported ENUM default in " + name + "." + colName + ": " + text);
            }
            return null;
        }
        if (isOpen(kind, text)) {
            depth++;
            if (depth == 2 && expectLabels && text.equals("(")) { inLabels = true; expectLabels = false; }
        } else if (isClose(kind, text)) {
            if (depth == 2) inLabels = false;
            if (--depth == 0) {
                closeColumn();
                tables.put(name.toString(), columns);
                stmt = Stmt.OTHER;
            }
        } else if (depth == 1 && kind == Kind.PUNCT && text.equals(",")) {
            closeColumn();
        } else if (depth == 1 && colPos == 0) {
            if (kind == Kind.WORD && CONSTRAINTS.contains(text)) colPos = 3;
            else { colName = normalizeToken(kind, text); colPos = 1; }
        } else if (depth == 1 && colPos == 1) {
            colPos = 2;
            if (kind == Kind.WORD && text.equals("ENUM")) { labels = new ArrayList<>(); expectLabels = true; }
            else if (kind == Kind.WORD && text.equals("NULL")) colNull = true;
        } else if (depth == 1 && colPos == 2 && labels != null && kind == Kind.WORD
                && (text.equals("DEFAULT") || text.equals("UPDATE"))) {
            expectDefault = true;
        } else if (inLabels && kind == Kind.STRING) {
            labels.add(text);
        }
        return null;
    }

    private void closeColumn() {
        if (colName != null && colPos != 3) columns.add(new Column(colName, colNull, labels));
        colPos = 0; colName = null; colNull = false; labels = null; expectLabels = false; inLabels = false;
        expectDefault = false;
    }

    private void insertToken(Kind kind, String text) {
        if (name.isEmpty() && kind == Kind.WORD && text.equals("INTO")) return;
        if (inColumnList) {
            if (kind == Kind.PUNCT && text.equals(")")) inColumnList = false;
            else if (kind == Kind.IDENT || kind == Kind.WORD) insertColumns.add(normalizeToken(kind, text));
            return;
        }
        if (kind == Kind.PUNCT && text.equals("(") && !name.isEmpty()) {
            inColumnList = true; insertColumns = new ArrayList<>(); return;
        }
        if (kind == Kind.WORD && text.equals("VALUES")) { startRows(); return; }
        if (kind == Kind.WORD && NON_VALUES.contains(text) && !name.isEmpty()) { stmt = Stmt.OTHER; return; }
        appendName(kind, text);
    }

    private void startRows() {
        List<Column> cols = tables.get(name.toString());
        enumFields = enumColumns(cols, insertColumns);
        rowWidth = insertColumns != null ? insertColumns.size() : cols == null ? 0 : cols.size();
        stmt = enumFields.isEmpty() ? Stmt.OTHER : Stmt.ROWS;
        depth = 0;
    }

    private static Map<Integer, List<String>> enumColumns(List<Column> cols, List<String> listed) {
        Map<Integer, List<String>> out = new HashMap<>();
        if (cols == null) return out;
        if (listed == null) {
            for (int i = 0; i < cols.size(); i++) if (cols.get(i).labels != null) out.put(i, cols.get(i).labels);
            return out;
        }
        for (Column c : cols) {
            if (c.labels == null) continue;
            int at = listed.indexOf(c.name);
            if (at >= 0) out.put(at, c.labels);
        }
        return out;
    }

    private String rowToken(Kind kind, String text, String upper) throws IOException {
        if (isOpen(kind, text)) {
            if (++depth == 1) { field = 0; fieldTokens = 0; return null; }
        } else if (isClose(kind, text)) {
            // a row whose width differs from the table means we mis-tokenized; never guess
            if (--depth == 0 && field + 1 != rowWidth) {
                throw new IOException("Row in " + name + " has " + (field + 1) + " values, table has "
                        + rowWidth + "; ENUM columns cannot be mapped safely");
            }
            return null;
        } else if (kind == Kind.PUNCT && text.equals(",") && depth <= 1) {
            if (depth == 1) { field++; fieldTokens = 0; }
            return null;
        }
        if (depth < 1 || !enumFields.containsKey(field)) return null;
        // an ENUM value must be a single ordinal, NULL or label
        if (fieldTokens++ > 0) {
            throw new IOException("Unexpected ENUM value in " + name + " column " + (field + 1) + ": " + text);
        }
        if (kind == Kind.STRING || (kind == Kind.WORD && upper.equals("NULL"))) return null;
        if (kind == Kind.WORD && text.chars().allMatch(Character::isDigit)) {
            return label(enumFields.get(field), text, "column " + (field + 1));
        }
        throw new IOException("Unexpected ENUM value in " + name + " column " + (field + 1) + ": " + text);
    }

    private String label(List<String> values, String ordinalText, String where) throws IOException {
        int ordinal;
        try {
            ordinal = Integer.parseInt(ordinalText);
        } catch (NumberFormatException e) {
            ordinal = Integer.MAX_VALUE;
        }
        if (ordinal >= values.size()) {
            throw new IOException("ENUM ordinal " + ordinalText + " out of range in " + name + " " + where);
        }
        ordinals++;
        return "'" + values.get(ordinal).replace("'", "''") + "'";
    }

    // ARRAY [..] nests like (..); its commas must not split columns or fields
    private static boolean isOpen(Kind kind, String text) {
        return kind == Kind.PUNCT && (text.equals("(") || text.equals("["));
    }

    private static boolean isClose(Kind kind, String text) {
        return kind == Kind.PUNCT && (text.equals(")") || text.equals("]"));
    }

    private void endStatement() {
        stmt = Stmt.START; depth = 0; name.setLength(0); nameDone = false;
        columns = null; colPos = 0; colName = null; colNull = false; labels = null;
        expectLabels = false; inLabels = false; expectDefault = false;
        insertColumns = null; inColumnList = false; enumFields = null;
    }

    private void startName() {
        name.setLength(0); nameDone = false;
    }

    private void appendName(Kind kind, String text) {
        if (kind == Kind.PUNCT && text.equals(".")) name.append('.');
        else if (kind == Kind.IDENT || kind == Kind.WORD) name.append(normalizeToken(kind, text));
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static String normalizeToken(Kind kind, String text) {
        return kind == Kind.IDENT ? text : text.toUpperCase(Locale.ROOT);
    }

    /** Normalised identifier: quoted parts as written, unquoted upper-cased. */
    private static String normalize(String ident) {
        String s = ident.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1).replace("\"\"", "\"");
        }
        return s.toUpperCase(Locale.ROOT);
    }

    /** Normalised dotted table reference, e.g. {@code "PUBLIC"."T"} -> {@code PUBLIC.T}. */
    static String key(String ref) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < ref.length() && ref.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else quoted = !quoted;
            } else if (c == '.' && !quoted) {
                parts.add(cur.toString()); cur.setLength(0);
            } else {
                cur.append(quoted ? c : Character.toUpperCase(c));
            }
        }
        parts.add(cur.toString());
        return String.join(".", parts);
    }
}
