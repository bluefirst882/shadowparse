package ai.toni.videoworkbench;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
class ResultValidator {
  /**
   * 引文归一化时丢弃空白与标点：模型常把原文的逗号/句号/引号改写，或把相邻片段之间的换行也带进引文，
   * 这类差异不算幻觉。只保留实义字符后再判断「包含」关系，既能容忍上述格式差异，又不会放行被改写、
   * 拼接或编造的句子——因此比较的是完整引文在原文中的连续包含，而不是前缀、关键字或编辑距离这类宽松匹配。
   */
  private static final Pattern IGNORED_IN_QUOTE_CHECK = Pattern.compile("[\\s\\p{Z}\\p{P}]");

  void validate(TaskResult result, List<TranscriptSegment> segments) {
    if (result.summary() == null || result.summary().isBlank())
      throw new Failure("empty_summary", "摘要不能为空");
    if (result.keyPoints() == null || result.keyPoints().stream().anyMatch(String::isBlank))
      throw new Failure("empty_key_points", "要点不能为空");
    Map<Long, Integer> segmentPositions =
        java.util.stream.IntStream.range(0, segments.size())
            .boxed()
            .collect(Collectors.toMap(index -> segments.get(index).id(), Function.identity()));
    if (result.chapters() == null) throw new Failure("invalid_chapters", "章节不能为空");
    long lastEnd = 0;
    for (int index = 0; index < result.chapters().size(); index++) {
      Chapter chapter = result.chapters().get(index);
      Integer startPosition = segmentPositions.get(chapter.sourceSegmentId());
      Integer endPosition = segmentPositions.get(chapter.sourceEndSegmentId());
      if (startPosition == null || endPosition == null)
        throw new Failure("invalid_chapter_reference", "章节未引用有效转写片段");
      TranscriptSegment startSource = segments.get(startPosition);
      TranscriptSegment endSource = segments.get(endPosition);
      if (endPosition < startPosition
          || chapter.startMs() < startSource.startMs()
          || chapter.endMs() > endSource.endMs()
          || chapter.endMs() <= chapter.startMs()
          || chapter.startMs() < lastEnd) throw new Failure("invalid_chapter_timing", "章节时间无效");
      if (chapter.title() == null || chapter.title().isBlank())
        throw new Failure("empty_chapter_title", "章节标题不能为空");
      verifyQuote(chapter, index, segments, startPosition, endPosition);
      lastEnd = chapter.endMs();
    }
  }

  /**
   * 反幻觉闸门：章节引文必须逐字出自 {@code sourceSegmentId..sourceEndSegmentId} 覆盖片段的转写原文。
   *
   * <p>归一化后若原文不包含引文，即判定为模型编造，抛出可供上层重试的异常。
   */
  private static void verifyQuote(
      Chapter chapter,
      int index,
      List<TranscriptSegment> segments,
      int startPosition,
      int endPosition) {
    String quote = chapter.quote();
    String label = "第 " + (index + 1) + " 个章节「" + titleOf(chapter) + "」";
    if (quote == null || quote.isBlank())
      throw new Failure("quote_missing", label + "缺少引文 quote，无法核验其出自转写原文");
    StringBuilder source = new StringBuilder();
    for (int position = startPosition; position <= endPosition; position++)
      source.append(segments.get(position).text());
    String normalizedQuote = normalizeForQuoteCheck(quote);
    if (normalizedQuote.isEmpty()
        || !normalizeForQuoteCheck(source.toString()).contains(normalizedQuote))
      throw new Failure("quote_mismatch", label + "的引文 quote 与所引用片段原文不符：quote=\"" + quote + "\"");
  }

  /** 引文核验前的归一化：删除空白与标点，仅保留实义字符用于连续包含比较。 */
  static String normalizeForQuoteCheck(String value) {
    return value == null ? "" : IGNORED_IN_QUOTE_CHECK.matcher(value).replaceAll("");
  }

  private static String titleOf(Chapter chapter) {
    return chapter.title() == null ? "" : chapter.title();
  }

  /** 校验失败：{@code reason} 是稳定分类，供指标按原因打点；消息面向调用方，需可读。 */
  static class Failure extends IllegalArgumentException {
    private final String reason;

    Failure(String reason, String message) {
      super(message);
      this.reason = reason;
    }

    String reason() {
      return reason;
    }
  }
}
