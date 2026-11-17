package dev.kmads.consignado.gateway.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Masks personal data for the public snapshot. The snapshot never holds a full name or a full CPF. */
public final class Masker {
    private Masker() {}

    private static final Set<String> NAME_KEYS = Set.of("name", "customerName", "fullName", "motherName");
    private static final Set<String> CPF_KEYS = Set.of("cpf", "customerCpf", "document");
    private static final Set<String> DROP_KEYS = Set.of("email", "phone", "address", "birthDate");

    /** "Maria da Silva" becomes "M**** d* S****". */
    public static String name(String value) {
        if (value == null || value.isBlank()) return "";
        StringBuilder out = new StringBuilder();
        for (String word : value.strip().split("\\s+")) {
            if (out.length() > 0) out.append(' ');
            int first = word.codePointAt(0);
            out.appendCodePoint(first);
            int rest = word.codePointCount(0, word.length()) - 1;
            out.append("*".repeat(Math.min(Math.max(rest, 1), 4)));
        }
        return out.toString();
    }

    /** Keeps only the 3 digits in the middle: "123.456.789-09" becomes "***.456.***-**". */
    public static String cpf(String value) {
        if (value == null) return "";
        String digits = value.replaceAll("\\D", "");
        if (digits.length() != 11) return "***.***.***-**";
        return "***." + digits.substring(3, 6) + ".***-**";
    }

    /** Masks a tree in place: person names, CPF, and contact fields at any depth. */
    public static JsonNode maskPersons(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> it = object.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> field = it.next();
                String key = field.getKey();
                JsonNode value = field.getValue();
                if (DROP_KEYS.contains(key)) {
                    it.remove();
                } else if (NAME_KEYS.contains(key) && value.isTextual()) {
                    field.setValue(TextNode.valueOf(name(value.asText())));
                } else if (CPF_KEYS.contains(key) && (value.isTextual() || value.isNumber())) {
                    field.setValue(TextNode.valueOf(cpf(value.asText())));
                } else {
                    maskPersons(value);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (JsonNode child : array) maskPersons(child);
        }
        return node;
    }
}
