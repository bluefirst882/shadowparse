package ai.toni.videoworkbench;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/tasks")
class TaskController {
  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 100;

  private final TaskService service;
  private final ExportService exports;

  TaskController(TaskService service, ExportService exports) {
    this.service = service;
    this.exports = exports;
  }

  @GetMapping
  TaskPage list(
      @RequestParam(value = "cursor", required = false) String cursor,
      @RequestParam(value = "limit", required = false) String limit,
      @AuthenticationPrincipal AuthenticatedUser user) {
    return service.list(
        user.id(), cursor == null ? null : TaskCursor.decode(cursor), parseLimit(limit));
  }

  private static int parseLimit(String value) {
    if (value == null) return DEFAULT_LIMIT;
    int limit;
    try {
      limit = Integer.parseInt(value.trim());
    } catch (NumberFormatException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit 必须为整数");
    }
    if (limit <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit 必须大于 0");
    if (limit > MAX_LIMIT)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit 不能超过 " + MAX_LIMIT);
    return limit;
  }

  @GetMapping("/{id}")
  VideoTask get(@PathVariable String id, @AuthenticationPrincipal AuthenticatedUser user) {
    return service.get(id, user.id());
  }

  @GetMapping("/{id}/details")
  TaskDetails details(@PathVariable String id, @AuthenticationPrincipal AuthenticatedUser user) {
    return service.details(id, user.id());
  }

  @PostMapping
  VideoTask create(
      @RequestParam("file") MultipartFile file, @AuthenticationPrincipal AuthenticatedUser user) {
    return service.importVideo(file, user.id());
  }

  @PostMapping("/{id}/cancel")
  void cancel(@PathVariable String id, @AuthenticationPrincipal AuthenticatedUser user) {
    service.cancel(id, user.id());
  }

  @PostMapping("/{id}/retry")
  void retry(@PathVariable String id, @AuthenticationPrincipal AuthenticatedUser user) {
    service.retry(id, user.id());
  }

  @PostMapping("/{id}/retranscribe")
  void retranscribe(@PathVariable String id, @AuthenticationPrincipal AuthenticatedUser user) {
    service.retranscribe(id, user.id());
  }

  @DeleteMapping("/{id}")
  void delete(@PathVariable String id, @AuthenticationPrincipal AuthenticatedUser user) {
    service.delete(id, user.id());
  }

  @GetMapping(value = "/{id}/export/{format}", produces = MediaType.TEXT_PLAIN_VALUE)
  ResponseEntity<String> export(
      @PathVariable String id,
      @PathVariable String format,
      @AuthenticationPrincipal AuthenticatedUser user) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=video-result." + format)
        .body(exports.export(id, format, user.id()));
  }

  @GetMapping("/{id}/video")
  ResponseEntity<Resource> stream(
      @PathVariable String id,
      @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
      @AuthenticationPrincipal AuthenticatedUser user)
      throws IOException {
    Resource video = service.video(id, user.id());
    long length = video.contentLength();
    if (range == null)
      return ResponseEntity.ok()
          .contentLength(length)
          .header(HttpHeaders.ACCEPT_RANGES, "bytes")
          .body(video);
    HttpRange requested = HttpRange.parseRanges(range).getFirst();
    long start = requested.getRangeStart(length),
        end = requested.getRangeEnd(length),
        count = end - start + 1;
    InputStream input = video.getInputStream();
    input.skipNBytes(start);
    InputStream bounded =
        new FilterInputStream(input) {
          long remaining = count;

          @Override
          public int read() throws IOException {
            if (remaining == 0) return -1;
            int value = super.read();
            if (value != -1) remaining--;
            return value;
          }

          @Override
          public int read(byte[] bytes, int offset, int size) throws IOException {
            if (remaining == 0) return -1;
            int read = super.read(bytes, offset, (int) Math.min(size, remaining));
            if (read != -1) remaining -= read;
            return read;
          }
        };
    return ResponseEntity.status(206)
        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
        .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + length)
        .contentLength(count)
        .body(
            new org.springframework.core.io.InputStreamResource(bounded) {
              @Override
              public long contentLength() {
                return count;
              }
            });
  }
}
