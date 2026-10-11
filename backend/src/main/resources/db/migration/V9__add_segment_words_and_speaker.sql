-- 词级时间戳（逐词高亮 / 点击定位）与说话人分离（多人会议 / 访谈）。
-- speaker 为 NULL 表示未做说话人分离；words 为 JSON 数组，元素结构见 TranscriptWord
-- （{start_ms,end_ms,text,speaker} 的蛇形/驼峰均可，反序列化端统一处理）。
-- 老数据的 words 留 NULL，读取端按「NULL 视为空列表」降级，不影响既有导出与摘要。
alter table transcript_segments
  add column speaker varchar(64) null after translation,
  add column words json null after speaker;
