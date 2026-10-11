package ai.toni.videoworkbench;

import java.nio.file.Path;

/**
 * 关键帧 OCR 引擎抽象。先实现 Tesseract（决策 #4），中文命中率不达标时换 PaddleOCR 容器只换实现。
 *
 * <p>实现应当是「一次调用一个识别」的无状态操作：调用方（{@link VisualExtractionService}） 自己控制并发与失败降级，引擎只负责把一张图变成文本。
 */
interface OcrEngine {
  /** 识别一张图片上的文字；识别失败抛异常，由调用方决定是否降级。返回空串表示图上没有可识别文字。 */
  String ocr(Path image) throws Exception;
}
