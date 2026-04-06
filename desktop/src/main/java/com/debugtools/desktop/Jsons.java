package com.debugtools.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class Jsons {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Jsons() {
    }

    public static String escape(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    public static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static JsonNode payload(JsonNode response) {
        if (response == null) return null;
        return response.get("payload");
    }

    public static String text(JsonNode node, String field, String fallback) {
        if (node == null || node.get(field) == null || node.get(field).isNull()) return fallback;
        return node.get(field).asText(fallback);
    }

    public static int integer(JsonNode node, String field, int fallback) {
        if (node == null || node.get(field) == null || node.get(field).isNull()) return fallback;
        return node.get(field).asInt(fallback);
    }
}
