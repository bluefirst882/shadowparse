package ai.toni.videoworkbench;

import java.util.List;

/**
 * 一段转写结果。
 *
 * <p>前 5 个字段为历史契约（id/startMs/endMs/text/translation），保持向后兼容的 5 参构造函数， 避免改动 20+ 处测试与 LlmClient
 * 的构造点。后两个字段是本次增强：
 *
 * <ul>
 *   <li>{@code speaker}：说话人标签（pyannote 分离结果），未分离时为 {@code null}；
 *   <li>{@code words}：词级时间戳列表（faster-whisper {@code word_timestamps}），未启用时为空列表。
 * </ul>
 */
public record TranscriptSegment(
    long id,
    long startMs,
    long endMs,
    String text,
    String translation,
    String speaker,
    List<TranscriptWord> words) {

  /** 兼容构造函数：旧调用点只传前 5 个字段，说话人与词级时间戳留空。 */
  public TranscriptSegment(long id, long startMs, long endMs, String text, String translation) {
    this(id, startMs, endMs, text, translation, null, List.of());
  }
}
