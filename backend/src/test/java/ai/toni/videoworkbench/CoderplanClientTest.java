package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class CoderplanClientTest {
  @Test
  void partitionsAtWholeTranscriptSegmentBoundaries() {
    List<TranscriptSegment> transcript =
        List.of(
            new TranscriptSegment(1, 0, 1000, "一", null),
            new TranscriptSegment(2, 1000, 2000, "二", null));
    int oneSegmentLimit = 60;

    List<List<TranscriptSegment>> chunks =
        CoderplanClient.partitionTranscript(transcript, oneSegmentLimit);

    assertEquals(List.of(1L), chunks.getFirst().stream().map(TranscriptSegment::id).toList());
    assertEquals(List.of(2L), chunks.get(1).stream().map(TranscriptSegment::id).toList());
  }

  @Test
  void rejectsSegmentThatCannotFitWithoutTruncation() {
    List<TranscriptSegment> transcript =
        List.of(new TranscriptSegment(1, 0, 1000, "很长的转写内容", null));

    assertThrows(
        IllegalArgumentException.class, () -> CoderplanClient.partitionTranscript(transcript, 10));
  }
}
