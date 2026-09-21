package com.moliys.tvbox;

/**
 * 「AI 建站」流水线的进度回调，用于把「现在正在干什么」实时显示到界面上。
 *
 * <p>回调发生在调用方所在线程（探测/写源/自检都在工作线程），界面侧自行切主线程。
 */
public interface AiSiteProgress {

    /** 什么都不做的实现，供不需要进度的调用方（含单元验证）使用。 */
    AiSiteProgress NONE = new AiSiteProgress() {

        @Override
        public void step(final String text) {
        }
    };

    /** 一条进度：一句话，不带换行。 */
    void step(String text);
}
