package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 校验本地 JSON Schema 校验器：它是「提示词里的 schema 原文」的服务端对应实现，必须能机械地指出违规位置与原因。 */
class LlmJsonSchemaTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void acceptsOutputMatchingSchema() throws Exception {
    assertEquals(List.of(), validate(validSummary()));
  }

  @Test
  void reportsMissingTopLevelField() throws Exception {
    JsonNode instance =
        json.readTree(
            """
            {"summary": "摘要", "keyPoints": ["要点"]}
            """);

    List<String> violations = LlmJsonSchema.validate(instance, LlmJsonSchema.SUMMARIZE.node());

    assertEquals(List.of("$.chapters 缺少必填字段"), violations);
  }

  @Test
  void reportsMissingNestedFieldWithPath() throws Exception {
    JsonNode instance =
        json.readTree(
            """
            {"summary": "摘要", "keyPoints": ["要点"], "chapters": [
              {"startMs": 0, "endMs": 1000, "title": "开场", "sourceSegmentId": 1, "sourceEndSegmentId": 1}
            ]}
            """);

    List<String> violations = LlmJsonSchema.validate(instance, LlmJsonSchema.SUMMARIZE.node());

    assertEquals(List.of("$.chapters[0].quote 缺少必填字段"), violations);
  }

  @Test
  void reportsWrongTypeWithPathAndActualType() throws Exception {
    JsonNode instance =
        json.readTree(
            """
            {"summary": "摘要", "keyPoints": ["要点"], "chapters": [
              {"startMs": 0, "endMs": 1000, "title": 5, "sourceSegmentId": 1, "sourceEndSegmentId": 1, "quote": "你好"}
            ]}
            """);

    List<String> violations = LlmJsonSchema.validate(instance, LlmJsonSchema.SUMMARIZE.node());

    assertEquals(List.of("$.chapters[0].title 期望 string，实际为 number"), violations);
  }

  @Test
  void reportsAllMissingFieldsOfAnArrayElement() throws Exception {
    JsonNode instance =
        json.readTree(
            """
            {"summary": "摘要", "keyPoints": ["要点"], "chapters": [{"startMs": 0}]}
            """);

    List<String> violations = LlmJsonSchema.validate(instance, LlmJsonSchema.SUMMARIZE.node());

    assertEquals(
        List.of(
            "$.chapters[0].endMs 缺少必填字段",
            "$.chapters[0].title 缺少必填字段",
            "$.chapters[0].sourceSegmentId 缺少必填字段",
            "$.chapters[0].sourceEndSegmentId 缺少必填字段",
            "$.chapters[0].quote 缺少必填字段"),
        violations);
  }

  @Test
  void acceptsIntegerWrittenAsDecimal() throws Exception {
    // 模型偶尔把整数写成 1000.0，Jackson 能转换，本地校验不该因此判违规触发重试。
    JsonNode instance =
        json.readTree(
            """
            {"summary": "摘要", "keyPoints": ["要点"], "chapters": [
              {"startMs": 0.0, "endMs": 1000.0, "title": "开场", "sourceSegmentId": 1, "sourceEndSegmentId": 1, "quote": "你好"}
            ]}
            """);

    assertEquals(List.of(), LlmJsonSchema.validate(instance, LlmJsonSchema.SUMMARIZE.node()));
  }

  @Test
  void reportsRootTypeMismatch() throws Exception {
    JsonNode instance = json.readTree("[]");

    assertEquals(
        List.of("$ 期望 object，实际为 array"),
        LlmJsonSchema.validate(instance, LlmJsonSchema.MERGE.node()));
  }

  @Test
  void capsViolationListToKeepErrorMessageBounded() throws Exception {
    StringBuilder chapters = new StringBuilder();
    for (int index = 0; index < 20; index++) {
      if (index > 0) chapters.append(',');
      chapters.append("{\"startMs\": 0}");
    }
    JsonNode instance =
        json.readTree("{\"summary\": \"摘要\", \"keyPoints\": [], \"chapters\": [" + chapters + "]}");

    List<String> violations = LlmJsonSchema.validate(instance, LlmJsonSchema.SUMMARIZE.node());

    assertEquals(11, violations.size());
    assertEquals("……其余违规已省略", violations.getLast());
  }

  @Test
  void validatesTranslateSchema() throws Exception {
    JsonNode missingTranslation =
        json.readTree(
            """
            {"translations": [{"id": 1}]}
            """);

    assertEquals(
        List.of("$.translations[0].translation 缺少必填字段"),
        LlmJsonSchema.validate(missingTranslation, LlmJsonSchema.TRANSLATE.node()));
  }

  private List<String> validate(String content) throws Exception {
    return LlmJsonSchema.validate(json.readTree(content), LlmJsonSchema.SUMMARIZE.node());
  }

  private String validSummary() {
    return """
        {"summary": "摘要", "keyPoints": ["要点"], "chapters": [
          {"startMs": 0, "endMs": 1000, "title": "开场", "sourceSegmentId": 1, "sourceEndSegmentId": 1, "quote": "你好"}
        ]}
        """;
  }
}
