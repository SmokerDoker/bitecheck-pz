package io.github.bitecheckpz.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parser for the strict TOML subset used by registry files.
 *
 * <p>Supported: comments, {@code [table]} and {@code [dotted.table]} headers, bare keys,
 * basic strings with escapes, integers, booleans and single-line arrays of those values.
 * Everything else (floats, dates, literal and multi-line strings, inline tables, arrays of
 * tables, dotted keys) is rejected with an error instead of being guessed at. Registry
 * files are also checked with a full TOML parser in CI, so they stay valid TOML.
 */
public final class Toml {

    private static final Pattern BARE_KEY = Pattern.compile("[A-Za-z0-9_-]+");

    private Toml() {
    }

    /** Error in a registry file, with file name and line number. */
    public static final class TomlException extends Exception {
        private static final long serialVersionUID = 1L;

        TomlException(String source, int line, String message) {
            super(source + ":" + line + ": " + message);
        }
    }

    public static Map<String, Object> parse(Path file) throws IOException, TomlException {
        return parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
    }

    public static Map<String, Object> parse(String text, String source) throws TomlException {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> current = root;
        Set<String> definedTables = new HashSet<>();
        String[] lines = text.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String line = stripComment(lines[i], source, lineNo).strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[[")) {
                throw new TomlException(source, lineNo, "arrays of tables are not supported");
            }
            if (line.startsWith("[")) {
                if (!line.endsWith("]")) {
                    throw new TomlException(source, lineNo, "table header must end with ']'");
                }
                String name = line.substring(1, line.length() - 1).strip();
                if (!definedTables.add(name)) {
                    throw new TomlException(source, lineNo, "table [" + name + "] defined twice");
                }
                current = openTable(root, name, source, lineNo);
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                throw new TomlException(source, lineNo, "expected 'key = value'");
            }
            String key = line.substring(0, eq).strip();
            if (!BARE_KEY.matcher(key).matches()) {
                throw new TomlException(source, lineNo, "unsupported key '" + key + "' (only bare keys)");
            }
            if (current.containsKey(key)) {
                throw new TomlException(source, lineNo, "key '" + key + "' defined twice");
            }
            Cursor cursor = new Cursor(line.substring(eq + 1), source, lineNo);
            Object value = cursor.value();
            cursor.skipWhitespace();
            if (!cursor.atEnd()) {
                throw new TomlException(source, lineNo, "unexpected text after value");
            }
            current.put(key, value);
        }
        return root;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> openTable(Map<String, Object> root, String name, String source, int lineNo)
            throws TomlException {
        Map<String, Object> table = root;
        for (String part : name.split("\\.", -1)) {
            String key = part.strip();
            if (!BARE_KEY.matcher(key).matches()) {
                throw new TomlException(source, lineNo, "unsupported table name '" + name + "'");
            }
            Object existing = table.get(key);
            if (existing == null) {
                Map<String, Object> created = new LinkedHashMap<>();
                table.put(key, created);
                table = created;
            } else if (existing instanceof Map) {
                table = (Map<String, Object>) existing;
            } else {
                throw new TomlException(source, lineNo, "'" + key + "' is already a value, not a table");
            }
        }
        return table;
    }

    private static String stripComment(String line, String source, int lineNo) throws TomlException {
        boolean inString = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '\'') {
                throw new TomlException(source, lineNo, "literal strings ('...') are not supported, use \"...\"");
            } else if (c == '#') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static final class Cursor {
        private final String s;
        private final String source;
        private final int lineNo;
        private int i;

        Cursor(String s, String source, int lineNo) {
            this.s = s;
            this.source = source;
            this.lineNo = lineNo;
        }

        boolean atEnd() {
            return i >= s.length();
        }

        void skipWhitespace() {
            while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
                i++;
            }
        }

        TomlException error(String message) {
            return new TomlException(source, lineNo, message);
        }

        Object value() throws TomlException {
            skipWhitespace();
            if (atEnd()) {
                throw error("missing value");
            }
            char c = s.charAt(i);
            if (c == '"') {
                return string();
            }
            if (c == '[') {
                return array();
            }
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            if (c == '+' || c == '-' || (c >= '0' && c <= '9')) {
                return integer();
            }
            throw error("unsupported value");
        }

        private String string() throws TomlException {
            if (s.startsWith("\"\"\"", i)) {
                throw error("multi-line strings are not supported");
            }
            i++; // opening quote
            StringBuilder out = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw error("unterminated string");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return out.toString();
                }
                if (c < 0x20 && c != '\t') {
                    throw error("control character in string");
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw error("unterminated escape");
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case 'u' -> {
                        if (i + 4 > s.length()) {
                            throw error("short \\u escape");
                        }
                        try {
                            out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw error("invalid \\u escape");
                        }
                        i += 4;
                    }
                    default -> throw error("unsupported escape \\" + e);
                }
            }
        }

        private Long integer() throws TomlException {
            int start = i;
            if (s.charAt(i) == '+' || s.charAt(i) == '-') {
                i++;
            }
            int digitsStart = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                i++;
            }
            String digits = s.substring(digitsStart, i);
            if (digits.isEmpty() || digits.startsWith("_") || digits.endsWith("_") || digits.contains("__")) {
                throw error("invalid integer");
            }
            if (i < s.length() && ".:eE-".indexOf(s.charAt(i)) >= 0) {
                throw error("floats and dates are not supported, use a string");
            }
            try {
                return Long.parseLong(s.substring(start, i).replace("_", ""));
            } catch (NumberFormatException ex) {
                throw error("integer out of range");
            }
        }

        private List<Object> array() throws TomlException {
            i++; // [
            List<Object> items = new ArrayList<>();
            while (true) {
                skipWhitespace();
                if (atEnd()) {
                    throw error("unterminated array (arrays must be on one line)");
                }
                if (s.charAt(i) == ']') {
                    i++;
                    return Collections.unmodifiableList(items);
                }
                items.add(value());
                skipWhitespace();
                if (atEnd()) {
                    throw error("unterminated array (arrays must be on one line)");
                }
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                } else if (c != ']') {
                    throw error("expected ',' or ']' in array");
                }
            }
        }
    }

    // ---- helpers for reading parsed documents ----

    /** Returns the sub-table at a dotted path, or an empty map if it does not exist. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> table(Map<String, Object> root, String dottedPath) {
        Map<String, Object> table = root;
        for (String part : dottedPath.split("\\.")) {
            Object next = table.get(part);
            if (!(next instanceof Map)) {
                return Map.of();
            }
            table = (Map<String, Object>) next;
        }
        return table;
    }

    /** Returns a string value, or {@code null} if the key is missing or not a string. */
    public static String string(Map<String, Object> table, String key) {
        Object value = table.get(key);
        return value instanceof String str ? str : null;
    }

    public static boolean bool(Map<String, Object> table, String key) {
        return Boolean.TRUE.equals(table.get(key));
    }

    /** Quotes a string as a TOML basic string. */
    public static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
