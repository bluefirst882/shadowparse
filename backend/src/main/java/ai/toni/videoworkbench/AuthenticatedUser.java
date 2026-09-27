package ai.toni.videoworkbench;

/** 令牌解析出的身份，同时也是任务归属判定的主体。 */
record AuthenticatedUser(String id, String username) {}
