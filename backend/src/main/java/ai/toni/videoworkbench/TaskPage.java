package ai.toni.videoworkbench;

import java.util.List;

/** 任务列表分页响应：当前页条目与下一页游标；{@code nextCursor} 为 null 表示没有更多数据。 */
public record TaskPage(List<VideoTask> items, String nextCursor) {}
