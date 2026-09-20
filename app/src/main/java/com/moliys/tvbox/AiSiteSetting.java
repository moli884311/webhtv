package com.moliys.tvbox;

import com.github.catvod.utils.Prefers;

/**
 * 「自动站点」的偏好读写。
 *
 * <p>只保存 AI 服务地址、模型名与知情同意状态；API Key 由用户自己填写，仅存本地
 * {@link Prefers}，不硬编码、不写日志、不写进站点配置文件、不通过任何接口回传明文。
 */
public final class AiSiteSetting {

    /** 默认服务地址：仅作可修改的占位默认值，不含任何凭据。 */
    public static final String DEFAULT_URL = "https://api.siliconflow.cn/v1/chat/completions";

    /** 默认模型名：仅作可修改的占位默认值。 */
    public static final String DEFAULT_MODEL = "Qwen/Qwen2-7B-Instruct";

    private static final String KEY_URL = "moliys_ai_url";
    private static final String KEY_KEY = "moliys_ai_key";
    private static final String KEY_MODEL = "moliys_ai_model";
    private static final String KEY_CONSENT = "moliys_ai_consent";

    private AiSiteSetting() {
    }

    public static String getUrl() {
        return orDefault(Prefers.getString(KEY_URL, ""), DEFAULT_URL);
    }

    public static void setUrl(final String url) {
        Prefers.put(KEY_URL, trim(url));
    }

    /** 返回用户填写的 API Key，未填写时为空串。调用方不得把它写入日志或配置文件。 */
    public static String getKey() {
        return trim(Prefers.getString(KEY_KEY, ""));
    }

    public static void setKey(final String key) {
        Prefers.put(KEY_KEY, trim(key));
    }

    public static boolean hasKey() {
        return !getKey().isEmpty();
    }

    public static String getModel() {
        return orDefault(Prefers.getString(KEY_MODEL, ""), DEFAULT_MODEL);
    }

    public static void setModel(final String model) {
        Prefers.put(KEY_MODEL, trim(model));
    }

    /** 是否已知晓「页面内容会发送给第三方模型服务」。 */
    public static boolean isConsented() {
        return Prefers.getBoolean(KEY_CONSENT, false);
    }

    public static void setConsented(final boolean consented) {
        Prefers.put(KEY_CONSENT, consented);
    }

    /** 是否具备调用 AI 的条件：已勾选知情同意，且已填写 Key。 */
    public static boolean canUseAi() {
        return isConsented() && hasKey();
    }

    private static String trim(final String text) {
        return text == null ? "" : text.trim();
    }

    private static String orDefault(final String value, final String fallback) {
        String text = trim(value);
        return text.isEmpty() ? fallback : text;
    }
}
