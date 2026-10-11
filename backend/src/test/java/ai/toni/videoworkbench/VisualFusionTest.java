package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 画面文本按时间归并进分块的口径：落在分块时间范围内的归属该分块、首尾之外的归最近分块、 无帧文本时不出现「画面文字」段。只测纯函数，不发起网络调用。 */
class VisualFusionTest {

  private static TranscriptSegment segment(long id, long startMs, long endMs) {
    return new TranscriptSegment(id, startMs, endMs, "片段" + id, null);
  }

  @Test
  void attachesFrameTextToTheChunkCoveringItsTimestamp() {
    List<List<TranscriptSegment>> chunks =
        List.of(List.of(segment(1, 0, 10_000)), List.of(segment(2, 20_000, 30_000)));
    List<FrameText> frames = List.of(new FrameText(5_000, "二叉树的前序遍历"));

    List<String> blocks = LlmClient.attachFrameTexts(chunks, frames);

    assertEquals(2, blocks.size());
    assertTrue(blocks.get(0).contains("[00:05] 二叉树的前序遍历"));
    assertTrue(blocks.get(0).contains("画面文字"), "归并后的块必须带画面文字标注");
    assertEquals("", blocks.get(1));
  }

  @Test
  void frameOutsideAnyChunkGoesToNearestChunk() {
    List<List<TranscriptSegment>> chunks =
        List.of(List.of(segment(1, 10_000, 20_000)), List.of(segment(2, 30_000, 40_000)));

    List<String> blocks =
        LlmClient.attachFrameTexts(
            chunks, List.of(new FrameText(2_000, "开场板书"), new FrameText(35_000, "结尾板书")));

    assertTrue(blocks.get(0).contains("开场板书"), "首块之前的帧归第一个分块");
    assertTrue(blocks.get(1).contains("结尾板书"), "块间空隙的帧归其后覆盖的最近分块");
  }

  @Test
  void blankFrameTextsAreDroppedAndEmptyFramesProduceNoSection() {
    List<List<TranscriptSegment>> chunks = List.of(List.of(segment(1, 0, 10_000)));

    assertEquals(
        List.of(""),
        LlmClient.attachFrameTexts(
            chunks, List.of(new FrameText(5_000, "  "), new FrameText(6_000, null))),
        "空白 OCR 文本不应进入提示词");
  }

  @Test
  void timestampFormatsMinutesAndHours() {
    assertEquals("01:05", LlmClient.timestamp(65_000));
    assertEquals("1:02:03", LlmClient.timestamp(3_723_000));
  }

  @Test
  void noFramesMeansNoVisualSection() {
    List<List<TranscriptSegment>> chunks = List.of(List.of(segment(1, 0, 10_000)));
    List<String> blocks = LlmClient.attachFrameTexts(chunks, List.of());
    assertEquals(List.of(""), blocks);
    assertFalse(blocks.getFirst().contains("画面文字"));
  }
}
