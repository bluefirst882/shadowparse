package ai.toni.videoworkbench;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM 各响应的 JSON Schema 唯一来源：同一份 schema 既写进提示词约束模型，也用于服务端本地机械校验。
 *
 * <p>DeepSeek 官方 API 目前只支持 {@code response_format={"type":"json_object"}}，{@code json_schema} 与强制
 * function calling 都会返回 400（实测见 README「LLM 结构化输出与可信校验」）。因此结构约束由 「提示词里的 schema 原文 +
 * 本地校验」承担：模型看得到精确字段表，服务端也不再只判断顶层字段是否为 null。
 *
 * <p>校验只覆盖本项目用到的 schema 子集（{@code type} / {@code required} / {@code properties} / {@code
 * items}），不处理 {@code additionalProperties} 等关键字——多出的字段由 Jackson 忽略， 不影响后续解析，没必要因此让整次调用失败。
 */
final class LlmJsonSchema {
  private static final ObjectMapper JSON = new ObjectMapper();

  /** 校验违规数的上限：避免章节很多时把错误信息撑成大段文本。 */
  private static final int MAX_VIOLATIONS = 10;

  static final Schema SUMMARIZE =
      of(
          """
          {
            "type": "object",
            "required": ["summary", "keyPoints", "chapters"],
            "properties": {
              "summary": { "type": "string" },
              "keyPoints": { "type": "array", "items": { "type": "string" } },
              "chapters": {
                "type": "array",
                "items": {
                  "type": "object",
                  "required": ["startMs", "endMs", "title", "sourceSegmentId", "sourceEndSegmentId", "quote"],
                  "properties": {
                    "startMs": { "type": "integer" },
                    "endMs": { "type": "integer" },
                    "title": { "type": "string" },
                    "sourceSegmentId": { "type": "integer" },
                    "sourceEndSegmentId": { "type": "integer" },
                    "quote": { "type": "string" }
                  }
                }
              }
            }
          }
          """);

  static final Schema MERGE =
      of(
          """
          {
            "type": "object",
            "required": ["summary", "keyPoints"],
            "properties": {
              "summary": { "type": "string" },
              "keyPoints": { "type": "array", "items": { "type": "string" } }
            }
          }
          """);

  static final Schema TRANSLATE =
      of(
          """
          {
            "type": "object",
            "required": ["translations"],
            "properties": {
              "translations": {
                "type": "array",
                "items": {
                  "type": "object",
                  "required": ["id", "translation"],
                  "properties": {
                    "id": { "type": "integer" },
                    "translation": { "type": "string" }
                  }
                }
              }
            }
          }
          """);

  private LlmJsonSchema() {}

  /** 一份 schema：{@code json} 用于写进提示词，{@code node} 用于本地校验。 */
  record Schema(String json, JsonNode node) {}

  /** 校验实例是否符合 schema，返回逐条带 JSON 路径的中文违规说明；空列表表示通过。 */
  static List<String> validate(JsonNode instance, JsonNode schema) {
    List<String> violations = new ArrayList<>();
    collect(instance, schema, "$", violations);
    if (violations.size() <= MAX_VIOLATIONS) return violations;
    List<String> capped = new ArrayList<>(violations.subList(0, MAX_VIOLATIONS));
    capped.add("……其余违规已省略");
    return capped;
  }

  private static void collect(
      JsonNode value, JsonNode schema, String path, List<String> violations) {
    if (violations.size() > MAX_VIOLATIONS) return;
    String type = schema.path("type").asText("");
    if (!type.isEmpty() && !matchesType(value, type)) {
      violations.add(path + " 期望 " + type + "，实际为 " + actualType(value));
      return;
    }
    if ("object".equals(type)) {
      for (JsonNode name : schema.path("required"))
        if (!value.has(name.asText())) violations.add(path + "." + name.asText() + " 缺少必填字段");
      JsonNode properties = schema.path("properties");
      properties
          .fieldNames()
          .forEachRemaining(
              name -> {
                if (value.has(name))
                  collect(value.get(name), properties.get(name), path + "." + name, violations);
              });
    } else if ("array".equals(type) && schema.has("items")) {
      for (int index = 0; index < value.size(); index++)
        collect(value.get(index), schema.path("items"), path + "[" + index + "]", violations);
    }
  }

  private static boolean matchesType(JsonNode value, String type) {
    return switch (type) {
      case "object" -> value.isObject();
      case "array" -> value.isArray();
      case "string" -> value.isTextual();
      // 模型偶尔把整数写成 1000.0，Jackson 能转换，这里也放行，避免无谓重试。
      case "integer" -> value.isIntegralNumber() || value.isNumber() && value.asDouble() % 1 == 0;
      case "number" -> value.isNumber();
      case "boolean" -> value.isBoolean();
      case "null" -> value.isNull();
      default -> true;
    };
  }

  private static String actualType(JsonNode value) {
    if (value.isNull()) return "null";
    if (value.isObject()) return "object";
    if (value.isArray()) return "array";
    if (value.isTextual()) return "string";
    if (value.isNumber()) return "number";
    if (value.isBoolean()) return "boolean";
    return value.getNodeType().name();
  }

  private static Schema of(String json) {
    try {
      return new Schema(json.strip(), JSON.readTree(json));
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("内置 JSON Schema 无法解析", ex);
    }
  }
}
