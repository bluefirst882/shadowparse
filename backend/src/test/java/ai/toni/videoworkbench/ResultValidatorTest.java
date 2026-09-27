package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ResultValidatorTest {
  private final ResultValidator validator = new ResultValidator();
  private final List<TranscriptSegment> transcript =
      List.of(
          new TranscriptSegment(7, 1000, 5000, "第一段讲清楚了背景。", null),
          new TranscriptSegment(8, 5000, 9000, "第二段给出结论", null));

  @Test
  void acceptsChapterInsideReferencedSegment() {
    assertDoesNotThrow(
        () ->
            validator.validate(
                new TaskResult(
                    "摘要",
                    List.of("要点"),
                    List.of(new Chapter(1000, 4000, "开始", 7, 7L, "第一段讲清楚了背景。"))),
                transcript));
  }

  @Test
  void rejectsChapterOutsideReferencedSegment() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            validator.validate(
                new TaskResult(
                    "摘要", List.of("要点"), List.of(new Chapter(0, 4000, "开始", 7, 7L, "第一段讲清楚了背景。"))),
                transcript));
  }

  @Test
  void rejectsMissingSourceSegment() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            validator.validate(
                new TaskResult(
                    "摘要",
                    List.of("要点"),
                    List.of(new Chapter(1000, 4000, "开始", 88, 88L, "第一段讲清楚了背景。"))),
                transcript));
  }

  @Test
  void acceptsTopicChapterAcrossConsecutiveSegments() {
    assertDoesNotThrow(
        () ->
            validator.validate(
                new TaskResult(
                    "摘要",
                    List.of("要点"),
                    List.of(new Chapter(1200, 8800, "主题", 7, 8L, "第一段讲清楚了背景。第二段给出结论"))),
                transcript));
  }

  @Test
  void rejectsChapterWithReversedSourceRange() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            validator.validate(
                new TaskResult(
                    "摘要", List.of("要点"), List.of(new Chapter(1200, 8800, "主题", 8, 7L, "第二段给出结论"))),
                transcript));
  }

  @Test
  void rejectsChapterOutsideReferencedRange() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            validator.validate(
                new TaskResult(
                    "摘要",
                    List.of("要点"),
                    List.of(new Chapter(900, 8800, "主题", 7, 8L, "第一段讲清楚了背景。"))),
                transcript));
  }

  @Test
  void acceptsQuoteThatOnlyDiffersInPunctuationAndWhitespace() {
    // 模型常见的格式差异：改写标点、插入空白、跨片段断行，都不应被误判为幻觉。
    assertDoesNotThrow(
        () ->
            validator.validate(
                new TaskResult(
                    "摘要",
                    List.of("要点"),
                    List.of(new Chapter(1000, 4000, "开始", 7, 7L, " 第一段，讲清楚了背景 "))),
                transcript));
  }

  @Test
  void rejectsQuoteNotPresentInReferencedSource() {
    ResultValidator.Failure failure =
        assertThrows(
            ResultValidator.Failure.class,
            () ->
                validator.validate(
                    new TaskResult(
                        "摘要",
                        List.of("要点"),
                        List.of(new Chapter(1000, 4000, "开始", 7, 7L, "这是模型编造的一句话"))),
                    transcript));

    assertEquals("quote_mismatch", failure.reason());
    assertTrue(failure.getMessage().contains("第 1 个章节"), failure.getMessage());
    assertTrue(failure.getMessage().contains("这是模型编造的一句话"), failure.getMessage());
  }

  @Test
  void rejectsQuoteBorrowedFromAnotherSegment() {
    // 引文确实来自转写，但不在该章节声明引用的片段范围内，仍属引用错误。
    ResultValidator.Failure failure =
        assertThrows(
            ResultValidator.Failure.class,
            () ->
                validator.validate(
                    new TaskResult(
                        "摘要",
                        List.of("要点"),
                        List.of(new Chapter(1000, 4000, "开始", 7, 7L, "第二段给出结论"))),
                    transcript));

    assertEquals("quote_mismatch", failure.reason());
  }

  @Test
  void rejectsChapterWithoutQuote() {
    ResultValidator.Failure failure =
        assertThrows(
            ResultValidator.Failure.class,
            () ->
                validator.validate(
                    new TaskResult("摘要", List.of("要点"), List.of(new Chapter(1000, 4000, "开始", 7))),
                    transcript));

    assertEquals("quote_missing", failure.reason());
  }
}
