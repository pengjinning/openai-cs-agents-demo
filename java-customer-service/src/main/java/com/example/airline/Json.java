package com.example.airline;

import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class Json {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {}

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static JsonNode read(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (JacksonException exception) {
            throw new ApiException(502, "INVALID_MODEL_JSON", "模型返回了无效 JSON");
        }
    }

    public static Map<String, String> arguments(String value) {
        JsonNode node = read(value);
        if (!node.isObject()) {
            throw new ApiException(502, "INVALID_TOOL_ARGUMENTS", "工具参数必须是对象");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (!entry.getValue().isString() || entry.getValue().asString().length() > 2000) {
                throw new ApiException(502, "INVALID_TOOL_ARGUMENTS", "工具参数必须是长度受限的字符串");
            }
            result.put(entry.getKey(), entry.getValue().asString());
        }
        return result;
    }
}
