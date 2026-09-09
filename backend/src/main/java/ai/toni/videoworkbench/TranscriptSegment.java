package ai.toni.videoworkbench;
public record TranscriptSegment(long id, long startMs, long endMs, String text, String translation) { }
