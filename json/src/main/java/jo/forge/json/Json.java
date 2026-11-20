package jo.forge.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Analyse et écriture JSON minimales, sans dépendance : manifeste signé des composants,
 * état installé, modèle déclaratif {@code forge.json}, trace de build.
 *
 * <p>Types acceptés en lecture : objets ({@code Map<String, Object>}, ordre conservé),
 * tableaux ({@code List<Object>}), chaînes, entiers ({@code Long}), décimaux
 * ({@code Double}), booléens et {@code null}. L'analyse est stricte : contenu résiduel
 * après la valeur, chaînes non fermées, échappements inconnus et profondeur excessive
 * sont rejetés.</p>
 *
 * <p>Ce parseur volontairement restreint ne sert qu'à des fichiers de confiance
 * vérifiés (manifeste signé, état interne) ou descriptifs de projet ; il n'est pas un
 * validateur général de JSON arbitraire.</p>
 */
public final class Json {

    /** Profondeur maximale d'imbrication acceptée. */
    private static final int MAX_DEPTH = 64;

    private final String text;
    private int position;
    private int depth;

    private Json(String text) {
        this.text = text;
    }

    /**
     * Analyse une chaîne JSON complète.
     *
     * @param json texte à analyser, jamais {@code null}.
     * @return la valeur racine (Map, List, String, Long, Double, Boolean ou null).
     * @throws JsonException si le texte est mal formé.
     */
    public static Object parse(String json) {
        if (json == null) {
            throw new JsonException("Entrée JSON nulle");
        }
        Json parser = new Json(json);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.position != json.length()) {
            throw new JsonException("Contenu résiduel après la valeur à la position "
                    + parser.position);
        }
        return value;
    }

    /**
     * Sérialise une valeur (Map, List, String, Number, Boolean, null) en JSON.
     *
     * @param value valeur à sérialiser.
     * @return le texte JSON correspondant.
     * @throws JsonException si un type n'est pas sérialisable.
     */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(value, out, 0);
        return out.toString();
    }

    // ---- Lecture ------------------------------------------------------------

    /**
     * Lit une valeur complète à la position courante.
     */
    private Object readValue() {
        if (position >= text.length()) {
            throw new JsonException("Fin de l'entrée inattendue");
        }
        char first = text.charAt(position);
        return switch (first) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't', 'f' -> readBoolean();
            case 'n' -> readNull();
            default -> readNumber();
        };
    }

    /**
     * Lit un objet JSON.
     */
    private Map<String, Object> readObject() {
        enter();
        Map<String, Object> object = new LinkedHashMap<>();
        position++; // '{'
        skipWhitespace();
        if (peek() == '}') {
            position++;
            depth--;
            return object;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw new JsonException("Clé de chaîne attendue à la position " + position);
            }
            String key = readString(); // consomme le guillemet ouvrant lui-même
            skipWhitespace();
            expect(':');
            skipWhitespace();
            object.put(key, readValue());
            skipWhitespace();
            char separator = peek();
            position++;
            if (separator == '}') {
                depth--;
                return object;
            }
            if (separator != ',') {
                throw new JsonException("Séparateur attendu dans l'objet à la position "
                        + (position - 1));
            }
        }
    }

    /**
     * Lit un tableau JSON.
     */
    private List<Object> readArray() {
        enter();
        List<Object> array = new ArrayList<>();
        position++; // '['
        skipWhitespace();
        if (peek() == ']') {
            position++;
            depth--;
            return array;
        }
        while (true) {
            skipWhitespace();
            array.add(readValue());
            skipWhitespace();
            char separator = peek();
            position++;
            if (separator == ']') {
                depth--;
                return array;
            }
            if (separator != ',') {
                throw new JsonException("Séparateur attendu dans le tableau à la position "
                        + (position - 1));
            }
        }
    }

    /**
     * Lit une chaîne JSON (guillemets déjà vérifiés).
     */
    private String readString() {
        position++; // '"'
        StringBuilder value = new StringBuilder();
        while (position < text.length()) {
            char character = text.charAt(position++);
            if (character == '"') {
                return value.toString();
            }
            if (character == '\\') {
                if (position >= text.length()) {
                    throw new JsonException("Échappement non terminé");
                }
                char escaped = text.charAt(position++);
                switch (escaped) {
                    case '"' -> value.append('"');
                    case '\\' -> value.append('\\');
                    case '/' -> value.append('/');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> {
                        if (position + 4 > text.length()) {
                            throw new JsonException("Séquence \\u incomplète");
                        }
                        String hex = text.substring(position, position + 4);
                        position += 4;
                        try {
                            value.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException badHex) {
                            throw new JsonException("Séquence \\u invalide : " + hex);
                        }
                    }
                    default -> throw new JsonException("Échappement inconnu : \\" + escaped);
                }
            } else if (character < 0x20) {
                throw new JsonException("Caractère de contrôle dans une chaîne à la position "
                        + (position - 1));
            } else {
                value.append(character);
            }
        }
        throw new JsonException("Chaîne non fermée");
    }

    /**
     * Lit un littéral booléen.
     */
    private Boolean readBoolean() {
        if (text.startsWith("true", position)) {
            position += 4;
            return Boolean.TRUE;
        }
        if (text.startsWith("false", position)) {
            position += 5;
            return Boolean.FALSE;
        }
        throw new JsonException("Littéral invalide à la position " + position);
    }

    /**
     * Lit le littéral null.
     */
    private Object readNull() {
        if (text.startsWith("null", position)) {
            position += 4;
            return null;
        }
        throw new JsonException("Littéral invalide à la position " + position);
    }

    /**
     * Lit un nombre (entier ou décimal).
     */
    private Number readNumber() {
        int start = position;
        if (peek() == '-') {
            position++;
        }
        boolean decimal = false;
        while (position < text.length()) {
            char character = text.charAt(position);
            if (character >= '0' && character <= '9') {
                position++;
            } else if (character == '.' || character == 'e' || character == 'E'
                    || character == '+' || character == '-') {
                decimal = true;
                position++;
            } else {
                break;
            }
        }
        String number = text.substring(start, position);
        if (number.isEmpty() || number.equals("-")) {
            throw new JsonException("Nombre attendu à la position " + start);
        }
        try {
            return decimal ? (Number) Double.parseDouble(number) : (Number) Long.parseLong(number);
        } catch (NumberFormatException badNumber) {
            throw new JsonException("Nombre invalide : " + number);
        }
    }

    private void enter() {
        if (++depth > MAX_DEPTH) {
            throw new JsonException("Profondeur d'imbrication excessive (max " + MAX_DEPTH + ")");
        }
    }

    private void skipWhitespace() {
        while (position < text.length()) {
            char character = text.charAt(position);
            if (character == ' ' || character == '\t' || character == '\n'
                    || character == '\r') {
                position++;
            } else {
                return;
            }
        }
    }

    private char peek() {
        if (position >= text.length()) {
            throw new JsonException("Fin de l'entrée inattendue");
        }
        return text.charAt(position);
    }

    private void expect(char expected) {
        if (peek() != expected) {
            throw new JsonException("Caractère '" + expected + "' attendu à la position "
                    + position);
        }
        position++;
    }

    // ---- Écriture -----------------------------------------------------------

    /**
     * Écrit une valeur JSON indentée.
     */
    private static void writeValue(Object value, StringBuilder out, int indent) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String string) {
            writeString(string, out);
        } else if (value instanceof Boolean || value instanceof Long
                || value instanceof Integer) {
            out.append(value);
        } else if (value instanceof Double decimal) {
            out.append(decimal.isNaN() || decimal.isInfinite() ? "null" : decimal);
        } else if (value instanceof Number number) {
            out.append(number);
        } else if (value instanceof Map<?, ?> map) {
            writeObject(map, out, indent);
        } else if (value instanceof List<?> list) {
            writeArray(list, out, indent);
        } else {
            throw new JsonException("Type non sérialisable : " + value.getClass().getName());
        }
    }

    private static void writeObject(Map<?, ?> map, StringBuilder out, int indent) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            newline(out, indent + 1);
            writeString(String.valueOf(entry.getKey()), out);
            out.append(": ");
            writeValue(entry.getValue(), out, indent + 1);
        }
        if (!map.isEmpty()) {
            newline(out, indent);
        }
        out.append('}');
    }

    private static void writeArray(List<?> list, StringBuilder out, int indent) {
        out.append('[');
        boolean first = true;
        for (Object item : list) {
            if (!first) {
                out.append(',');
            }
            first = false;
            newline(out, indent + 1);
            writeValue(item, out, indent + 1);
        }
        if (!list.isEmpty()) {
            newline(out, indent);
        }
        out.append(']');
    }

    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (character < 0x20) {
                        out.append(String.format("\\u%04x", (int) character));
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        out.append('"');
    }

    private static void newline(StringBuilder out, int indent) {
        out.append('\n');
        out.append("  ".repeat(indent));
    }
}
