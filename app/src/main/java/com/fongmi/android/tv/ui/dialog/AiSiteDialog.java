package com.fongmi.android.tv.ui.dialog;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterAiSiteBinding;
import com.fongmi.android.tv.databinding.DialogAiSiteBinding;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.moliys.tvbox.AiSite;
import com.moliys.tvbox.AiSiteClient;
import com.moliys.tvbox.AiSiteSetting;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 「AI 自动站点」面板：输入一个网站地址，识别出可用的采集接口并加入站点列表。
 *
 * <p>识别顺序按 D7 探测优先：先看输入本身是不是接口，再从首页 HTML 里找线索，
 * 都不命中且用户已配置 AI 时才把页面内容发给模型。只有探测与 AI 都失败才报错，
 * 且任何失败都不会改动已有的站点配置。站点识别成功后会把该分组设为当前接口配置，
 * 否则新站点不会出现在影视主页的站源列表里。
 */
public class AiSiteDialog extends BaseAlertDialog {

    private static final long FETCH_TIMEOUT = 15000L;

    private DialogAiSiteBinding binding;
    private SiteAdapter adapter;
    private Runnable callback;
    private boolean running;
    private boolean changed;

    public static void show(Fragment fragment) {
        show(fragment, null);
    }

    public static void show(Fragment fragment, Runnable callback) {
        AiSiteDialog dialog = new AiSiteDialog();
        dialog.callback = callback;
        dialog.show(fragment.getChildFragmentManager(), null);
    }

    public static void show(FragmentActivity activity) {
        show(activity, null);
    }

    public static void show(FragmentActivity activity, Runnable callback) {
        AiSiteDialog dialog = new AiSiteDialog();
        dialog.callback = callback;
        dialog.show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogAiSiteBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog).setView(getBinding().getRoot());
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() == null) return;
        Window window = getDialog().getWindow();
        if (window == null) return;
        WindowManager.LayoutParams params = window.getAttributes();
        int screenWidth = ResUtil.getScreenWidth(requireContext());
        int screenHeight = ResUtil.getScreenHeight(requireContext());
        boolean land = ResUtil.isLand(requireContext());
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.getDecorView().setPadding(0, 0, 0, 0);
        params.width = (int) (screenWidth * (land ? 0.72f : 0.92f));
        params.height = land ? (int) (screenHeight * 0.98f) : WindowManager.LayoutParams.WRAP_CONTENT;
        window.setAttributes(params);
        window.setLayout(params.width, params.height);
        ViewGroup.LayoutParams rootParams = binding.root.getLayoutParams();
        rootParams.height = land ? params.height : ViewGroup.LayoutParams.WRAP_CONTENT;
        binding.root.setLayoutParams(rootParams);
        LinearLayoutCompat.LayoutParams scrollParams = (LinearLayoutCompat.LayoutParams) binding.contentScroll.getLayoutParams();
        scrollParams.height = land ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT;
        scrollParams.weight = land ? 1 : 0;
        binding.contentScroll.setLayoutParams(scrollParams);
        binding.contentScroll.setMaxHeight(land ? 0 : (int) (screenHeight * 0.52f));
        binding.target.requestFocus();
    }

    @Override
    protected void initView() {
        binding.aiUrl.setText(AiSiteSetting.getUrl());
        binding.aiModel.setText(AiSiteSetting.getModel());
        binding.aiKey.setText(AiSiteSetting.getKey());
        binding.consent.setChecked(AiSiteSetting.isConsented());
        adapter = new SiteAdapter();
        binding.recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recycler.setItemAnimator(null);
        binding.recycler.setAdapter(adapter);
        refreshSites();
        updateActions();
    }

    @Override
    protected void initEvent() {
        binding.target.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                updateActions();
            }
        });
        binding.consent.setOnCheckedChangeListener((button, checked) -> {
            AiSiteSetting.setConsented(checked);
            updateActions();
        });
        binding.recognize.setOnClickListener(view -> onRecognize());
        binding.negative.setOnClickListener(view -> dismiss());
        binding.positive.setOnClickListener(view -> onPositive());
    }

    // ---------------------------------------------------------------- 交互状态

    private void updateActions() {
        binding.recognize.setEnabled(!running && !textOf(binding.target).isEmpty() && binding.consent.isChecked());
    }

    private void refreshSites() {
        JSONArray sites = AiSite.loadSites(App.get());
        adapter.setItems(sites);
        binding.empty.setVisibility(sites.length() == 0 ? View.VISIBLE : View.GONE);
    }

    private void persistFields() {
        AiSiteSetting.setUrl(textOf(binding.aiUrl));
        AiSiteSetting.setKey(textOf(binding.aiKey));
        AiSiteSetting.setModel(textOf(binding.aiModel));
        AiSiteSetting.setConsented(binding.consent.isChecked());
    }

    private void onPositive() {
        if (!textOf(binding.aiKey).isEmpty() && !isHttp(textOf(binding.aiUrl))) {
            Notify.show(R.string.ai_site_invalid_key);
            return;
        }
        persistFields();
        if (callback != null) callback.run();
        Notify.show(R.string.ai_site_saved);
        dismiss();
    }

    private void onDelete(final String key) {
        if (key.isEmpty() || !AiSite.removeSite(App.get(), key)) {
            Notify.show(R.string.ai_site_delete_fail);
            return;
        }
        changed = true;
        AiSite.activate(App.get(), () -> App.post(() -> {
            refreshSites();
            Notify.show(R.string.ai_site_deleted);
            if (callback != null) callback.run();
        }));
    }

    // ---------------------------------------------------------------- 识别（D7）

    private void onRecognize() {
        if (running) return;
        String target = textOf(binding.target);
        if (target.isEmpty()) {
            Notify.show(R.string.ai_site_need_url);
            return;
        }
        if (!binding.consent.isChecked()) {
            Notify.show(R.string.ai_site_need_consent);
            return;
        }
        persistFields();
        changed = false;
        running = true;
        updateActions();
        Notify.show(R.string.ai_site_running);
        Task.execute(() -> {
            String message;
            try {
                message = recognize(target);
            } catch (Throwable e) {
                message = ResUtil.getString(R.string.ai_site_add_fail, brief(e));
            }
            String output = message;
            App.post(() -> finishRecognize(output));
        });
    }

    /** 识别结束后收尾：只有真的加了站点才切换当前接口配置，否则不打扰用户已有的配置。 */
    private void finishRecognize(final String message) {
        running = false;
        updateActions();
        if (!changed) {
            Notify.show(message);
            if (callback != null) callback.run();
            return;
        }
        AiSite.activate(App.get(), () -> App.post(() -> {
            refreshSites();
            Notify.show(message);
            if (callback != null) callback.run();
        }));
    }

    /** 返回给用户看的一句话结果。 */
    private String recognize(final String target) {
        if (AiSite.looksLikeApi(target)) return addOne(probeSite(target));
        String html = fetch(target);
        JSONObject homepage = AiSite.fromHomepageHtml(target, html);
        if (homepage != null) return addOne(homepage);
        if (!AiSiteSetting.canUseAi()) return ResUtil.getString(R.string.ai_site_need_ai);
        JSONObject result = AiSiteClient.detect(target, html, AiSiteSetting.getUrl(), AiSiteSetting.getKey(), AiSiteSetting.getModel());
        if (!result.optBoolean("ok")) {
            String error = result.optString("error", "").trim();
            return error.isEmpty() ? ResUtil.getString(R.string.ai_site_need_ai) : error;
        }
        return addOne(result);
    }

    private JSONObject probeSite(final String api) {
        try {
            JSONObject site = new JSONObject();
            site.put("name", hostName(api));
            site.put("api", api);
            site.put("type", AiSite.fetchType(api));
            return site;
        } catch (Throwable e) {
            return null;
        }
    }

    private String addOne(final JSONObject site) {
        if (site == null) return ResUtil.getString(R.string.ai_site_add_fail, "");
        String error = AiSite.addSite(App.get(), site);
        if (error.isEmpty()) changed = true;
        return error.isEmpty() ? ResUtil.getString(R.string.ai_site_added_ok) : ResUtil.getString(R.string.ai_site_add_fail, error);
    }

    private String fetch(final String url) {
        try {
            String body = OkHttp.string(url, FETCH_TIMEOUT);
            return body == null ? "" : body;
        } catch (Throwable e) {
            return "";
        }
    }

    private static String textOf(final TextView view) {
        if (view == null || view.getText() == null) return "";
        return view.getText().toString().trim();
    }

    private static String hostName(final String url) {
        try {
            String host = Uri.parse(url).getHost();
            return TextUtils.isEmpty(host) ? "" : host;
        } catch (Throwable e) {
            return "";
        }
    }

    /** 与 {@code AiSite.isHttpUrl} 同义：必须是带主机名的 http/https 地址。 */
    private static boolean isHttp(final String url) {
        String text = url == null ? "" : url.trim();
        if (!text.startsWith("http://") && !text.startsWith("https://")) return false;
        try {
            return !TextUtils.isEmpty(Uri.parse(text).getHost());
        } catch (Throwable e) {
            return false;
        }
    }

    private static String brief(final Throwable error) {
        String message = error == null ? "" : String.valueOf(error.getMessage());
        if (TextUtils.isEmpty(message)) message = error == null ? "" : error.getClass().getSimpleName();
        return message.length() > 120 ? message.substring(0, 120) : message;
    }

    // ---------------------------------------------------------------- 列表

    private class SiteAdapter extends RecyclerView.Adapter<SiteAdapter.ViewHolder> {

        private final List<JSONObject> items = new ArrayList<>();

        void setItems(final JSONArray sites) {
            items.clear();
            for (int i = 0; sites != null && i < sites.length(); i++) {
                JSONObject site = sites.optJSONObject(i);
                if (site != null) items.add(site);
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(AdapterAiSiteBinding.inflate(getLayoutInflater(), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.bind(items.get(position));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {

            private final AdapterAiSiteBinding binding;

            ViewHolder(final AdapterAiSiteBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }

            void bind(final JSONObject site) {
                binding.name.setText(site.optString("name", ""));
                binding.api.setText(site.optString("api", ""));
                binding.delete.setOnClickListener(view -> onDelete(itemKey(site)));
            }
        }
    }

    private static String itemKey(final JSONObject site) {
        return site == null ? "" : site.optString("key", "").trim();
    }
}
