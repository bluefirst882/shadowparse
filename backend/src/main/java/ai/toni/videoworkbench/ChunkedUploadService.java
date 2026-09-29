package ai.toni.videoworkbench;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 分片上传：大文件按固定大小的分片逐片传输，中途断网/关页面后重新选择同一个文件即可从缺失的分片继续。
 *
 * <p>进度状态直接放在文件系统里，不额外建表：一个上传一个目录，里面有 {@code meta.properties}（文件名、总大小、归属人）、 {@code
 * data.part}（按偏移直接写入的原始数据）以及每片一个 {@code chunk-N.done} 标记。这样进程重启后进度还在，
 * 也不需要给「半截上传」设计一套数据库保留策略。标记在数据写完之后才创建，因此「有标记」等价于「该片已经落盘」。
 *
 * <p>{@code uploadId} 由「归属人 + 文件名 + 大小」派生：同一个文件重选时会得到同一个 id，于是天然续传；反过来 也不能拿别人的 id 来写数据，因为 {@code
 * meta} 里记着归属人，对不上直接 403，不靠「id 猜不到」兜底。
 */
@Service
class ChunkedUploadService {
  /** 分片大小：既能限制单次请求体大小，又不至于让进度条跳得太粗。 */
  static final int CHUNK_SIZE = 5 * 1024 * 1024;

  private static final String META_FILE = "meta.properties";
  private static final String DATA_FILE = "data.part";
  private static final String MARKER_PREFIX = "chunk-";
  private static final String MARKER_SUFFIX = ".done";

  private final TaskService tasks;
  private final Path root;
  private final long maxUploadBytes;

  ChunkedUploadService(
      TaskService tasks,
      @Value("${workbench.storage-dir}") String storageDir,
      @Value("${workbench.max-upload-bytes}") long maxUploadBytes) {
    this.tasks = tasks;
    this.root = Path.of(storageDir).resolve("uploads");
    this.maxUploadBytes = maxUploadBytes;
  }

  /** 客户端看到的会话状态：已有分片用序号列表表达，接着传缺失的那几片即可。 */
  record Session(
      String uploadId,
      String fileName,
      long sizeBytes,
      int chunkSize,
      List<Integer> receivedChunks) {}

  /** 会话元数据；{@code ownerId} 既是归属校验依据，也参与 uploadId 的派生。 */
  private record Meta(String fileName, long sizeBytes, int chunkSize, String ownerId) {}

  /** 开始（或继续）一次上传。同一账号重新选择同一个文件时会拿到同样的 {@code uploadId} 与已收分片列表， 因此续传对客户端是「无状态」的：不需要它自己记住上次传到哪。 */
  Session init(String fileName, long sizeBytes, String ownerId) {
    if (!TaskService.isVideo(null, fileName))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择有效的视频文件");
    if (sizeBytes <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件大小必须大于 0");
    if (sizeBytes > maxUploadBytes)
      throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "视频文件超过允许大小");
    String uploadId = uploadId(ownerId, fileName, sizeBytes);
    Path dir = root.resolve(uploadId);
    try {
      Files.createDirectories(dir);
      Path meta = dir.resolve(META_FILE);
      if (!Files.exists(meta)) writeMeta(meta, new Meta(fileName, sizeBytes, CHUNK_SIZE, ownerId));
      return session(uploadId, dir, readMeta(meta));
    } catch (IOException ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "无法创建上传会话", ex);
    }
  }

  Session status(String uploadId, String ownerId) {
    Path dir = root.resolve(uploadId);
    return session(uploadId, dir, requireMeta(dir, ownerId));
  }

  /** 写入一片。分片可以乱序到达，按 {@code index * chunkSize} 的偏移直接写进 {@code data.part}。 */
  Session putChunk(String uploadId, String ownerId, int index, byte[] body) {
    Path dir = root.resolve(uploadId);
    Meta meta = requireMeta(dir, ownerId);
    if (index < 0 || index >= chunkCount(meta))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分片序号超出范围");
    if (body == null || body.length == 0)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分片内容为空");
    long offset = (long) index * meta.chunkSize();
    if (body.length > meta.chunkSize() || offset + body.length > meta.sizeBytes())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分片大小与声明的文件大小不匹配");
    try {
      Path data = dir.resolve(DATA_FILE);
      try (RandomAccessFile file = new RandomAccessFile(data.toFile(), "rw")) {
        file.seek(offset);
        file.write(body);
      }
      // 标记最后写：任何时刻看到标记，就说明这一片的数据已经完整落盘。
      Files.write(dir.resolve(marker(index)), new byte[0]);
      return session(uploadId, dir, meta);
    } catch (IOException ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "无法保存分片", ex);
    }
  }

  /** 收齐后交给 {@link TaskService} 登记成任务；登记成功才清掉整个会话目录。 */
  VideoTask complete(String uploadId, String ownerId) {
    Path dir = root.resolve(uploadId);
    Meta meta = requireMeta(dir, ownerId);
    if (receivedChunks(dir).size() < chunkCount(meta))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "还有分片未上传完成");
    Path data = dir.resolve(DATA_FILE);
    try {
      // 元数据说多大就应该收到多大：缺片、被截断都在这里拦下，绝不登记一个不完整的视频。
      if (!Files.exists(data) || Files.size(data) != meta.sizeBytes())
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "已收到的数据大小与声明不一致");
      VideoTask task = tasks.registerVideo(data, meta.fileName(), ownerId);
      deleteRecursively(dir);
      return task;
    } catch (IOException ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "无法完成上传", ex);
    }
  }

  private Session session(String uploadId, Path dir, Meta meta) {
    return new Session(
        uploadId, meta.fileName(), meta.sizeBytes(), meta.chunkSize(), receivedChunks(dir));
  }

  private Meta requireMeta(Path dir, String ownerId) {
    Path meta = dir.resolve(META_FILE);
    if (!Files.exists(meta)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "上传会话不存在或已完成");
    Meta session = readMeta(meta);
    // uploadId 里虽然含归属人，但路径参数是客户端给的，归属校验必须落在会话自己记录的归属人上。
    if (!session.ownerId().equals(ownerId)) throw new AccessDeniedException("无权访问该上传会话");
    return session;
  }

  private static int chunkCount(Meta meta) {
    return (int) ((meta.sizeBytes() + meta.chunkSize() - 1) / meta.chunkSize());
  }

  private static String marker(int index) {
    return MARKER_PREFIX + index + MARKER_SUFFIX;
  }

  private List<Integer> receivedChunks(Path dir) {
    try (Stream<Path> files = Files.list(dir)) {
      List<Integer> received =
          files
              .map(path -> path.getFileName().toString())
              .filter(name -> name.startsWith(MARKER_PREFIX) && name.endsWith(MARKER_SUFFIX))
              .map(
                  name ->
                      name.substring(
                          MARKER_PREFIX.length(), name.length() - MARKER_SUFFIX.length()))
              .map(Integer::parseInt)
              .sorted(Comparator.naturalOrder())
              .toList();
      return new ArrayList<>(received);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  private void writeMeta(Path meta, Meta value) throws IOException {
    Properties properties = new Properties();
    properties.setProperty("fileName", value.fileName());
    properties.setProperty("sizeBytes", Long.toString(value.sizeBytes()));
    properties.setProperty("chunkSize", Integer.toString(value.chunkSize()));
    properties.setProperty("ownerId", value.ownerId());
    try (var out = Files.newOutputStream(meta)) {
      properties.store(out, "chunked upload session");
    }
  }

  private Meta readMeta(Path meta) {
    Properties properties = new Properties();
    try (var in = Files.newInputStream(meta)) {
      properties.load(in);
    } catch (IOException ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "上传会话元数据不可读", ex);
    }
    return new Meta(
        properties.getProperty("fileName"),
        Long.parseLong(properties.getProperty("sizeBytes")),
        Integer.parseInt(properties.getProperty("chunkSize")),
        properties.getProperty("ownerId"));
  }

  private static String uploadId(String ownerId, String fileName, long sizeBytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash =
          digest.digest(
              (ownerId + "\n" + fileName + "\n" + sizeBytes).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash, 0, 16);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("JVM 不支持 SHA-256", ex);
    }
  }

  private static void deleteRecursively(Path dir) throws IOException {
    if (!Files.exists(dir)) return;
    try (Stream<Path> files = Files.walk(dir)) {
      for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
    }
  }
}
