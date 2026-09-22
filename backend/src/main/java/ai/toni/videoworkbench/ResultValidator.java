package ai.toni.videoworkbench;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
class ResultValidator {
  void validate(TaskResult result, List<TranscriptSegment> segments) {
    if (result.summary() == null || result.summary().isBlank())
      throw new IllegalArgumentException("摘要不能为空");
    if (result.keyPoints() == null || result.keyPoints().stream().anyMatch(String::isBlank))
      throw new IllegalArgumentException("要点不能为空");
    Map<Long, Integer> segmentPositions =
        java.util.stream.IntStream.range(0, segments.size())
            .boxed()
            .collect(Collectors.toMap(index -> segments.get(index).id(), Function.identity()));
    if (result.chapters() == null) throw new IllegalArgumentException("章节不能为空");
    long lastEnd = 0;
    for (Chapter chapter : result.chapters()) {
      Integer startPosition = segmentPositions.get(chapter.sourceSegmentId());
      Integer endPosition = segmentPositions.get(chapter.sourceEndSegmentId());
      if (startPosition == null || endPosition == null)
        throw new IllegalArgumentException("章节未引用有效转写片段");
      TranscriptSegment startSource = segments.get(startPosition);
      TranscriptSegment endSource = segments.get(endPosition);
      if (endPosition < startPosition
          || chapter.startMs() < startSource.startMs()
          || chapter.endMs() > endSource.endMs()
          || chapter.endMs() <= chapter.startMs()
          || chapter.startMs() < lastEnd
          || chapter.title() == null
          || chapter.title().isBlank()) throw new IllegalArgumentException("章节时间或标题无效");
      lastEnd = chapter.endMs();
    }
  }
}
