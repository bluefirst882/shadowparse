package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

/** pHash 的口径：同图同哈希、近图近哈希、异图远哈希；去重阈值据此校准。 */
class PerceptualHashTest {

  private static BufferedImage gradient() {
    BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 64; y++)
      for (int x = 0; x < 64; x++) image.setRGB(x, y, (x * 4) * 0x010101);
    return image;
  }

  private static BufferedImage solid(int rgb) {
    BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) image.setRGB(x, y, rgb);
    return image;
  }

  @Test
  void sameImageHasIdenticalHash() {
    BufferedImage image = gradient();
    assertEquals(PerceptualHash.hash(image), PerceptualHash.hash(image));
    assertEquals(
        0, PerceptualHash.distance(PerceptualHash.hash(image), PerceptualHash.hash(image)));
  }

  @Test
  void slightlyChangedImageStaysWithinDuplicateThreshold() {
    // 渐变图整体抬亮 1 个色阶：低频结构不变，只有个别系数跨过中位数，距离应远小于去重阈值。
    // （纯色图不能用：所有系数几乎相等，中位数阈值本身就不稳定。）
    BufferedImage image = gradient();
    BufferedImage variant = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 64; y++)
      for (int x = 0; x < 64; x++) variant.setRGB(x, y, Math.min(255, x * 4 + 1) * 0x010101);
    long distance =
        PerceptualHash.distance(PerceptualHash.hash(image), PerceptualHash.hash(variant));
    assertTrue(
        distance <= PerceptualHash.DUPLICATE_DISTANCE, "轻微亮度变化的画面应判为重复（distance=" + distance + "）");
  }

  @Test
  void structurallyDifferentImageIsFarAway() {
    // 一半黑一半白 vs 全白：低频结构完全不同，汉明距离必须远离去重阈值。
    BufferedImage half = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 64; y++)
      for (int x = 0; x < 64; x++) half.setRGB(x, y, x < 32 ? 0x000000 : 0xFFFFFF);
    long distance =
        PerceptualHash.distance(PerceptualHash.hash(half), PerceptualHash.hash(solid(0xFFFFFF)));
    assertTrue(
        distance > PerceptualHash.DUPLICATE_DISTANCE, "结构不同的画面不应判为重复（distance=" + distance + "）");
  }
}
