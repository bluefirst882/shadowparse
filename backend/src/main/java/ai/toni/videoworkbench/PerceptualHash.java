package ai.toni.videoworkbench;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

/**
 * 64 位感知哈希（pHash）：缩放到 32x32 灰度 → 二维 DCT → 取左上 8x8 低频系数的中位数阈值。
 *
 * <p>手写实现而不引入 JImageHash：算法只有几十行且需要完全可控的测试口径， 为一个工具引入带传递依赖的库不划算。相邻帧汉明距离不超过阈值即视为同一画面（见 {@link
 * VisualExtractionService} 的去重参数）。
 */
final class PerceptualHash {
  /**
   * 两个哈希的汉明距离不超过该值即认为画面相同（10/63 ≈ 16%，pHash 去重的常用边界，含边界本身）： 32x32 缩放下对轻微压缩噪声足够稳定，而真实画面切换的距离通常在 20
   * 以上。
   */
  static final int DUPLICATE_DISTANCE = 10;

  private PerceptualHash() {}

  static long hash(BufferedImage image) {
    double[][] gray = grayscale(resize(image, 32, 32));
    double[][] dct = dct2d(gray);
    // 丢掉 DC 分量 [0][0]（只反映整体亮度，对画面切换不敏感），其余 63 个低频系数取中位数做阈值。
    double[] coefficients = new double[63];
    int index = 0;
    for (int y = 0; y < 8; y++)
      for (int x = 0; x < 8; x++) {
        if (x == 0 && y == 0) continue;
        coefficients[index++] = dct[y][x];
      }
    double median = median(coefficients);
    long hash = 0;
    index = 0;
    for (int y = 0; y < 8; y++)
      for (int x = 0; x < 8; x++) {
        if (x == 0 && y == 0) continue;
        if (dct[y][x] > median) hash |= 1L << index;
        index++;
      }
    return hash;
  }

  static int distance(long a, long b) {
    return Long.bitCount(a ^ b);
  }

  private static BufferedImage resize(BufferedImage source, int width, int height) {
    BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
    Graphics2D graphics = scaled.createGraphics();
    try {
      graphics.drawImage(source, 0, 0, width, height, null);
    } finally {
      graphics.dispose();
    }
    return scaled;
  }

  private static double[][] grayscale(BufferedImage image) {
    byte[] pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
    double[][] gray = new double[image.getHeight()][image.getWidth()];
    for (int y = 0; y < image.getHeight(); y++)
      for (int x = 0; x < image.getWidth(); x++)
        gray[y][x] = pixels[y * image.getWidth() + x] & 0xFF;
    return gray;
  }

  /** 二维 DCT-II，按行列两次一维变换；32x32 规模下朴素实现足够快。 */
  private static double[][] dct2d(double[][] input) {
    int size = input.length;
    double[][] rows = new double[size][size];
    for (int y = 0; y < size; y++) rows[y] = dct1d(input[y]);
    double[][] columns = new double[size][size];
    double[] column = new double[size];
    for (int x = 0; x < size; x++) {
      for (int y = 0; y < size; y++) column[y] = rows[y][x];
      double[] transformed = dct1d(column);
      for (int y = 0; y < size; y++) columns[y][x] = transformed[y];
    }
    return columns;
  }

  private static double[] dct1d(double[] input) {
    int size = input.length;
    double[] result = new double[size];
    for (int k = 0; k < size; k++) {
      double sum = 0;
      for (int n = 0; n < size; n++)
        sum += input[n] * Math.cos(Math.PI * (2L * n + 1) * k / (2.0 * size));
      result[k] = sum * (k == 0 ? Math.sqrt(1.0 / size) : Math.sqrt(2.0 / size));
    }
    return result;
  }

  private static double median(double[] values) {
    double[] sorted = values.clone();
    java.util.Arrays.sort(sorted);
    int middle = sorted.length / 2;
    return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2.0;
  }
}
