package io.github.bitecheckpz.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small JSON reader/writer used to edit ZombieBuddy's approval file without losing anything.
 *
 * <p>Objects become {@link LinkedHashMap} (key order kept), arrays {@link ArrayList}, strings
 * {@link String}, booleans {@link Boolean}, null {@code null} and numbers {@link Num}, which keeps
 * the original text so large ids such as SteamID64 values are written back unchanged.
 */
public final class Json {

    private Json() {
    }

    /** A JSON number, kept as its original text. */
    public record Num(String text) {
        public static Num of(long value) {
            return new Num(Long.toString(value));
        }
    }

    public static final class JsonException extends Exception {
        private static final long serialVersionUID = 1L;

        JsonException(String message, int pos) {
            super(message + " at offset " + pos);
        }
    }

    public static Object parse(String text) throws JsonException {
        Parser p = new Parser(text);
        p.ws();
        Object value = p.value(0);
        p.ws();
        if (p.i != text.length()) {
            throw new JsonException("unexpected text after JSON value", p.i);
        }
        return value;
    }

    private static final class Parser {
        private static final int MAX_DEPTH = 64;
        final String s;
        int i;

        Parser(String s) {
            this.s = s;
        }

        void ws() {
            while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
        }

        char peek() throws JsonException {
            if (i >= s.length()) {
                throw new JsonException("unexpected end of JSON", i);
            }
            return s.charAt(i);
        }

        void expect(char c) throws JsonException {
            if (peek() != c) {
                throw new JsonException("expected '" + c + "'", i);
            }
            i++;
        }

        Object value(int depth) throws JsonException {
            if (depth > MAX_DEPTH) {
                throw new JsonException("nested too deeply", i);
            }
            char c = peek();
            switch (c) {
                case '{':
                    return object(depth);
                case '[':
                    return array(depth);
                case '"':
                    return string();
                case 't':
                    return literal("true", Boolean.TRUE);
                case 'f':
                    return literal("false", Boolean.FALSE);
                case 'n':
                    return literal("null", null);
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return number();
                    }
                    throw new JsonException("unexpected character '" + c + "'", i);
            }
        }

        Object literal(String word, Object value) throws JsonException {
            if (!s.startsWith(word, i)) {
                throw new JsonException("invalid literal", i);
            }
            i += word.length();
            return value;
        }

        Map<String, Object> object(int depth) throws JsonException {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            ws();
            if (peek() == '}') {
                i++;
                return map;
            }
            while (true) {
                ws();
                String key = string();
                ws();
                expect(':');
                ws();
                map.put(key, value(depth + 1));
                ws();
                if (peek() == ',') {
                    i++;
                    continue;
                }
                expect('}');
                return map;
            }
        }

        List<Object> array(int depth) throws JsonException {
            expect('[');
            List<Object> list = new ArrayList<>();
            ws();
            if (peek() == ']') {
                i++;
                return list;
            }
            while (true) {
                ws();
                list.add(value(depth + 1));
                ws();
                if (peek() == ',') {
                    i++;
                    continue;
                }
                expect(']');
                return list;
            }
        }

        String string() throws JsonException {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = peek();
                i++;
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char e = peek();
                i++;
                switch (e) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) {
                            throw new JsonException("short \\u escape", i);
                        }
                        try {
                            out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw new JsonException("invalid \\u escape", i);
                        }
                        i += 4;
                    }
                    default -> throw new JsonException("invalid escape", i);
                }
            }
        }

        Num number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            return new Num(s.substring(start, i));
        }
    }

    /** Writes a value as indented JSON (2 spaces), similar to Gson's pretty printing. */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, 0);
        return out.append('\n').toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object value, StringBuilder out, int indent) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String str) {
            quote(str, out);
        } else if (value instanceof Boolean b) {
            out.append(b);
        } else if (value instanceof Num n) {
            out.append(n.text());
        } else if (value instanceof Number n) {
            out.append(n);
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            int k = 0;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) map).entrySet()) {
                pad(out, indent + 1);
                quote(e.getKey(), out);
                out.append(": ");
                write(e.getValue(), out, indent + 1);
                out.append(++k < map.size() ? ",\n" : "\n");
            }
            pad(out, indent);
            out.append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append("[\n");
            for (int k = 0; k < list.size(); k++) {
                pad(out, indent + 1);
                write(list.get(k), out, indent + 1);
                out.append(k + 1 < list.size() ? ",\n" : "\n");
            }
            pad(out, indent);
            out.append(']');
        } else {
            throw new IllegalArgumentException("cannot write " + value.getClass());
        }
    }

    private static void pad(StringBuilder out, int indent) {
        out.append("  ".repeat(indent));
    }

    private static void quote(String s, StringBuilder out) {
        out.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
