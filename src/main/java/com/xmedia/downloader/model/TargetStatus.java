package com.xmedia.downloader.model;

/** ID 列表里单个目标的校验状态。 */
public enum TargetStatus {
    /** 还没校验过（新加入） */
    UNKNOWN,
    /** 校验通过：ID 有效 */
    VALID,
    /** 校验失败：该 ID 无效（账号不存在/已停用），界面会提醒删除 */
    INVALID
}
