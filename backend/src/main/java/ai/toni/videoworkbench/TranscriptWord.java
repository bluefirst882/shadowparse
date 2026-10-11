package ai.toni.videoworkbench;

/**
 * 词级时间戳：用于字幕逐词高亮与点击定位。
 *
 * <p>时间相对整段视频（毫秒），与 {@link TranscriptSegment} 的 {@code startMs/endMs} 同一坐标系； 说话人 {@code speaker}
 * 仅在启用分离时非空，且优先取词自身标注（分段级说话人回灌到每个词上）。
 */
public record TranscriptWord(long startMs, long endMs, String text, String speaker) {}
