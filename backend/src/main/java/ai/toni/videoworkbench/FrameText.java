package ai.toni.videoworkbench;

/**
 * 一帧关键帧及其 OCR 文本，进入摘要提示词的「画面文字」上下文。
 *
 * <p>OCR 文本只作上下文参考：识别有错，不能当引文来源——章节的 {@code sourceSegmentId} 与 {@code quote} 闸门仍然只认转写片段（见 {@link
 * ResultValidator}）。
 */
record FrameText(long tsMs, String text) {}
