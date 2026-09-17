package ai.toni.videoworkbench;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/tasks")
class TaskController {
  private final TaskService service;
  private final ExportService exports;

  TaskController(TaskService service, ExportService exports) {
    this.service = service;
    this.exports = exports;
  }

  @GetMapping
  List<VideoTask> list() {
    return service.list();
  }

  @GetMapping("/{id}")
  VideoTask get(@PathVariable String id) {
    return service.get(id);
  }

  @GetMapping("/{id}/details")
  TaskDetails details(@PathVariable String id) {
    return service.details(id);
  }

  @PostMapping
  VideoTask create(@RequestParam("file") MultipartFile file) {
    return service.importVideo(file);
  }

  @PostMapping("/{id}/cancel")
  void cancel(@PathVariable String id) {
    service.cancel(id);
  }

  @PostMapping("/{id}/retry")
  void retry(@PathVariable String id) {
    service.retry(id);
  }

  @PostMapping("/{id}/retranscribe")
  void retranscribe(@PathVariable String id) {
    service.retranscribe(id);
  }

  @DeleteMapping("/{id}")
  void delete(@PathVariable String id) {
    service.delete(id);
  }

  @GetMapping(value = "/{id}/export/{format}", produces = MediaType.TEXT_PLAIN_VALUE)
  ResponseEntity<String> export(@PathVariable String id, @PathVariable String format) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=video-result." + format)
        .body(exports.export(id, format));
  }

  @GetMapping("/{id}/video")
  ResponseEntity<Resource> stream(
      @PathVariable String id,
      @RequestHeader(value = HttpHeaders.RANGE, required = false) String range)
      throws IOException {
    Resource video = service.video(id);
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
