package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 关键帧去重与封顶的纯函数口径：相邻同画面只留一帧，超上限按时间轴均匀抽样。 不触碰 ffmpeg 与文件系统，候选帧用假的路径与哈希构造。 */
class VisualFrameDedupTest {
  private static VisualExtractionService.VideoExtractionFrame frame(long tsMs, long phash) {
    return new VisualExtractionService.VideoExtractionFrame(tsMs, Path.of("unused-" + tsMs), phash);
  }

  @Test
  void dropsAdjacentFramesWithinDuplicateDistance() {
    long base = 0b1111_0000_1111_0000L;
    List<VisualExtractionService.VideoExtractionFrame> kept =
        VisualExtractionService.dedupeAndCap(
            List.of(frame(0, base), frame(500, base + 0b11), frame(1000, ~base)), 200);

    // 第 2 帧与第 1 帧距离 2 < 10 被丢弃；第 3 帧与基准完全相反必须保留。
    assertEquals(2, kept.size());
    assertEquals(0, kept.get(0).tsMs());
    assertEquals(1000, kept.get(1).tsMs());
  }

  @Test
  void keepsOnlyOneFramePerTimestampEvenWhenPicturesDiffer() {
    // 生产事故回放：场景通道的真第 0 帧与 fps 网格错位取到的「第 0 帧」内容不同，
    // pHash 距离超过去重阈值，两帧同 ts 落库撞唯一键。时间戳相同必须只留一个，
    // 且稳定排序下场景通道的帧先加入，留下的应是它。
    long sceneFirstFrame = splitMix64(1);
    long misalignedGridFrame = splitMix64(2);
    List<VisualExtractionService.VideoExtractionFrame> kept =
        VisualExtractionService.dedupeAndCap(
            List.of(frame(0, sceneFirstFrame), frame(0, misalignedGridFrame)), 200);

    assertEquals(1, kept.size());
    assertEquals(sceneFirstFrame, kept.get(0).phash());
  }

  @Test
  void capsByEvenlySamplingTheTimeline() {
    // 300 个互不重复的帧，上限 200：保留的应是均匀抽样的 200 帧，首尾必须包含。
    // splitmix64 保证相邻序号的哈希在雪崩后相距足够远（>> 10 位），不会被去重误杀。
    var builder =
        java.util.stream.LongStream.range(0, 300)
            .mapToObj(index -> frame(index * 1000, splitMix64(index)))
            .toList();
    List<VisualExtractionService.VideoExtractionFrame> kept =
        VisualExtractionService.dedupeAndCap(builder, 200);

    assertEquals(200, kept.size());
    assertEquals(0, kept.getFirst().tsMs());
    assertEquals(299000, kept.getLast().tsMs());
  }

  /** splitmix64 终混：相邻输入的输出在比特上充分发散，用作合成「互不重复」的哈希。 */
  private static long splitMix64(long index) {
    long z = index + 0x9E3779B97F4A7C15L;
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }
}
