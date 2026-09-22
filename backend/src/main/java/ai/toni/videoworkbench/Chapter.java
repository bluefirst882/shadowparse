package ai.toni.videoworkbench;

/** A topic chapter may cover one or more consecutive transcript segments. */
public record Chapter(
    long startMs, long endMs, String title, long sourceSegmentId, Long sourceEndSegmentId) {
  public Chapter {
    if (sourceEndSegmentId == null) sourceEndSegmentId = sourceSegmentId;
  }

  public Chapter(long startMs, long endMs, String title, long sourceSegmentId) {
    this(startMs, endMs, title, sourceSegmentId, sourceSegmentId);
  }
}
