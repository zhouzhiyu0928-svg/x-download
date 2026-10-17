package com.xmedia.downloader.model;

/** 媒体类型（与数据库 download_record.media_type 取值一致）。 */
public enum MediaType {
    IMAGE("image"),
    VIDEO("video"),
    GIF("gif");

    private final String label;

    MediaType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static MediaType fromLabel(String label) {
        for (MediaType t : values()) {
            if (t.label.equalsIgnoreCase(label)) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知媒体类型：" + label);
    }
}
