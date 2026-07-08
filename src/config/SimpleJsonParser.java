package config;

import java.io.*;
import java.util.*;

/**
 * Simple JSON parser without external libraries.
 * Handles basic JSON parsing for configuration files.
 */
public class SimpleJsonParser {
    private String json;
    private int pos;

    public SimpleJsonParser(String json) {
        this.json = json;
        this.pos = 0;
    }

    public static SimpleJsonParser fromFile(String filePath) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return new SimpleJsonParser(sb.toString());
    }

    public Object parse() {
        skipWhitespace();
        return parseValue();
    }

    public Map<String, Object> parseObject() {
        skipWhitespace();
        if (current() != '{') {
            throw new RuntimeException("Expected '{' at position " + pos);
        }
        pos++; // consume '{'

        Map<String, Object> map = new HashMap<>();
        skipWhitespace();

        if (current() == '}') {
            pos++;
            return map;
        }

        while (true) {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();
            if (current() != ':') {
                throw new RuntimeException("Expected ':' at position " + pos);
            }
            pos++; // consume ':'
            skipWhitespace();
            Object value = parseValue();
            map.put(key, value);

            skipWhitespace();
            if (current() == '}') {
                pos++;
                break;
            } else if (current() == ',') {
                pos++; // consume ','
            } else {
                throw new RuntimeException("Expected ',' or '}' at position " + pos);
            }
        }

        return map;
    }

    public List<Object> parseArray() {
        skipWhitespace();
        if (current() != '[') {
            throw new RuntimeException("Expected '[' at position " + pos);
        }
        pos++; // consume '['

        List<Object> list = new ArrayList<>();
        skipWhitespace();

        if (current() == ']') {
            pos++;
            return list;
        }

        while (true) {
            skipWhitespace();
            list.add(parseValue());
            skipWhitespace();

            if (current() == ']') {
                pos++;
                break;
            } else if (current() == ',') {
                pos++; // consume ','
            } else {
                throw new RuntimeException("Expected ',' or ']' at position " + pos);
            }
        }

        return list;
    }

    private Object parseValue() {
        skipWhitespace();
        char c = current();

        if (c == '{') {
            return parseObject();
        } else if (c == '[') {
            return parseArray();
        } else if (c == '"') {
            return parseString();
        } else if (c == 't' || c == 'f') {
            return parseBoolean();
        } else if (c == 'n') {
            return parseNull();
        } else if (c == '-' || (c >= '0' && c <= '9')) {
            return parseNumber();
        } else {
            throw new RuntimeException("Unexpected character: " + c + " at position " + pos);
        }
    }

    private String parseString() {
        if (current() != '"') {
            throw new RuntimeException("Expected '\"' at position " + pos);
        }
        pos++; // consume opening quote

        StringBuilder sb = new StringBuilder();
        while (pos < json.length()) {
            char c = json.charAt(pos);
            if (c == '"') {
                pos++;
                return sb.toString();
            } else if (c == '\\') {
                pos++;
                if (pos >= json.length()) {
                    throw new RuntimeException("Unexpected end of string");
                }
                char escaped = json.charAt(pos);
                switch (escaped) {
                    case '"':
                    case '\\':
                    case '/':
                        sb.append(escaped);
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (pos + 4 >= json.length()) {
                            throw new RuntimeException("Invalid unicode escape");
                        }
                        String hex = json.substring(pos + 1, pos + 5);
                        sb.append((char) Integer.parseInt(hex, 16));
                        pos += 4;
                        break;
                    default:
                        sb.append(escaped);
                }
                pos++;
            } else {
                sb.append(c);
                pos++;
            }
        }
        throw new RuntimeException("Unterminated string at position " + pos);
    }

    private Number parseNumber() {
        StringBuilder sb = new StringBuilder();
        if (current() == '-') {
            sb.append(current());
            pos++;
        }

        while (pos < json.length() && json.charAt(pos) >= '0' && json.charAt(pos) <= '9') {
            sb.append(json.charAt(pos));
            pos++;
        }

        if (pos < json.length() && json.charAt(pos) == '.') {
            sb.append(json.charAt(pos));
            pos++;
            while (pos < json.length() && json.charAt(pos) >= '0' && json.charAt(pos) <= '9') {
                sb.append(json.charAt(pos));
                pos++;
            }
        }

        if (pos < json.length() && (json.charAt(pos) == 'e' || json.charAt(pos) == 'E')) {
            sb.append(json.charAt(pos));
            pos++;
            if (pos < json.length() && (json.charAt(pos) == '+' || json.charAt(pos) == '-')) {
                sb.append(json.charAt(pos));
                pos++;
            }
            while (pos < json.length() && json.charAt(pos) >= '0' && json.charAt(pos) <= '9') {
                sb.append(json.charAt(pos));
                pos++;
            }
        }

        String numStr = sb.toString();
        try {
            if (numStr.contains(".") || numStr.contains("e") || numStr.contains("E")) {
                return Double.parseDouble(numStr);
            } else {
                return Long.parseLong(numStr);
            }
        } catch (NumberFormatException e) {
            throw new RuntimeException("Invalid number: " + numStr);
        }
    }

    private Boolean parseBoolean() {
        if (json.startsWith("true", pos)) {
            pos += 4;
            return true;
        } else if (json.startsWith("false", pos)) {
            pos += 5;
            return false;
        } else {
            throw new RuntimeException("Invalid boolean at position " + pos);
        }
    }

    private Object parseNull() {
        if (json.startsWith("null", pos)) {
            pos += 4;
            return null;
        } else {
            throw new RuntimeException("Invalid null at position " + pos);
        }
    }

    private void skipWhitespace() {
        while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) {
            pos++;
        }
    }

    private char current() {
        if (pos >= json.length()) {
            return '\0';
        }
        return json.charAt(pos);
    }

    // Utility methods for type conversion
    public static long parseSize(String sizeStr) {
        if (sizeStr == null) return 0;
        sizeStr = sizeStr.trim().toUpperCase();
        long multiplier = 1;

        if (sizeStr.endsWith("GB")) {
            multiplier = 1024 * 1024 * 1024L;
            sizeStr = sizeStr.substring(0, sizeStr.length() - 2).trim();
        } else if (sizeStr.endsWith("MB")) {
            multiplier = 1024 * 1024;
            sizeStr = sizeStr.substring(0, sizeStr.length() - 2).trim();
        } else if (sizeStr.endsWith("KB")) {
            multiplier = 1024;
            sizeStr = sizeStr.substring(0, sizeStr.length() - 2).trim();
        } else if (sizeStr.endsWith("B")) {
            sizeStr = sizeStr.substring(0, sizeStr.length() - 1).trim();
        }

        try {
            return Long.parseLong(sizeStr) * multiplier;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
