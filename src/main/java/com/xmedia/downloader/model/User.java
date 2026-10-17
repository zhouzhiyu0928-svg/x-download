package com.xmedia.downloader.model;

/** 用户（screen_name 可变更，rest_id 才是稳定键 —— H5）。 */
public record User(String restId, String screenName, String name, int statusesCount, int mediaCount) {
}
