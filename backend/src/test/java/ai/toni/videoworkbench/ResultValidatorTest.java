package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class ResultValidatorTest {
  private final ResultValidator validator = new ResultValidator();
  private final List<TranscriptSegment> transcript =
      List.of(new TranscriptSegment(7, 1000, 5000, "第一段", null));

  @Test
  void acceptsChapterInsideReferencedSegment() {
    assertDoesNotThrow(
        () ->
            validator.validate(
                new TaskResult("摘要", List.of("要点"), List.of(new Chapter(1000, 4000, "开始", 7))),
                transcript));
  }

  @Test
  void rejectsChapterOutsideReferencedSegment() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            validator.validate(
                new TaskResult("摘要", List.of("要点"), List.of(new Chapter(0, 4000, "开始", 7))),
                transcript));
  }

  @Test
  void rejectsMissingSourceSegment() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            validator.validate(
                new TaskResult("摘要", List.of("要点"), List.of(new Chapter(1000, 4000, "开始", 88))),
                transcript));
  }
}
