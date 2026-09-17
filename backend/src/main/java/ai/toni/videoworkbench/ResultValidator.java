package ai.toni.videoworkbench;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
class ResultValidator {
  void validate(TaskResult result, List<TranscriptSegment> segments) {
    if (result.summary() == null || result.summary().isBlank())
      throw new IllegalArgumentException("摘要不能为空");
    if (result.keyPoints() == null || result.keyPoints().stream().anyMatch(String::isBlank))
      throw new IllegalArgumentException("要点不能为空");
    long lastEnd = 0;
    for (Chapter chapter : result.chapters()) {
      TranscriptSegment source =
          segments.stream()
              .filter(s -> s.id() == chapter.sourceSegmentId())
              .findFirst()
              .orElseThrow(() -> new IllegalArgumentException("章节未引用有效转写片段"));
      if (chapter.startMs() < source.startMs()
          || chapter.endMs() > source.endMs()
          || chapter.endMs() <= chapter.startMs()
          || chapter.startMs() < lastEnd
          || chapter.title() == null
          || chapter.title().isBlank()) throw new IllegalArgumentException("章节时间或标题无效");
      lastEnd = chapter.endMs();
    }
  }
}
