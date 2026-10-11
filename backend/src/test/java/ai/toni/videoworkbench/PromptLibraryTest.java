package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** 验证提示词资源确实从带版本号的文件加载，且 schema 占位符来自 {@link LlmJsonSchema} 这一唯一来源。 */
class PromptLibraryTest {

  @Test
  void loadsVersionedResourcesAndInjectsPlaceholders() {
    String prompt =
        PromptLibrary.SUMMARIZE.user(
            Map.of(
                "schema",
                LlmJsonSchema.SUMMARIZE.json(),
                "retryHint",
                "",
                "visualText",
                "",
                "input",
                "[id=1] 你好"));

    // v2 相对 v1 只增加 {{visualText}} 占位符（P1 视觉分支），无画面文本时传空串，行为与 v1 等价。
    assertEquals("summarize.v2", PromptLibrary.SUMMARIZE.id());
    assertTrue(PromptLibrary.SUMMARIZE.system().contains("只返回合法 JSON"));
    assertTrue(PromptLibrary.SUMMARIZE.system().contains("OCR"), "v2 必须声明画面文字不可作为引文来源");
    assertTrue(prompt.contains(LlmJsonSchema.SUMMARIZE.json()), "提示词必须内嵌 schema 原文");
    assertTrue(prompt.contains("[id=1] 你好"), "提示词必须包含输入文本");
    assertFalse(prompt.contains("{{"), "占位符必须全部被替换");
    assertFalse(prompt.contains("画面文字"), "无画面文本时不应出现画面文字段");
    assertFalse(prompt.contains("上次输出未通过校验"), "首次调用不应出现重试提示");
  }

  @Test
  void everyPromptEmbedsItsOwnSchemaSoPromptAndValidationCannotDrift() {
    assertTrue(
        PromptLibrary.MERGE
            .user(Map.of("schema", LlmJsonSchema.MERGE.json(), "retryHint", "", "input", "块"))
            .contains(LlmJsonSchema.MERGE.json()));
    assertTrue(
        PromptLibrary.MERGE
            .user(Map.of("schema", LlmJsonSchema.MERGE.json(), "retryHint", "", "input", "块"))
            .contains("合并去重"));
    assertTrue(
        PromptLibrary.TRANSLATE
            .user(Map.of("schema", LlmJsonSchema.TRANSLATE.json(), "input", "字幕"))
            .contains(LlmJsonSchema.TRANSLATE.json()));
  }

  @Test
  void rejectsMissingPlaceholderValueInsteadOfSilentlyLeavingItBlank() {
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> PromptLibrary.SUMMARIZE.user(Map.of("schema", "{}", "input", "文本")));

    assertTrue(failure.getMessage().contains("retryHint"), "错误信息应指出缺少哪个占位符取值");
  }
}
