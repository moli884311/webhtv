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
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.moliys.tvbox.AiSite;
import com.moliys.tvbox.AiSiteClient;
import com.moliys.tvbox.AiSiteProbe;
import com.moliys.tvbox.AiSiteSelfTest;
import com.moliys.tvbox.AiSiteSetting;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 「AI 自动站点」面板：输入一个网站地址，识别出可用的采集接口并加入站点列表。
 *
 * <p>建站走四步（§12.3）：探测（本机，不耗 AI）→ AI 写源 → 本机自检 → 落盘注册。
 * 新站以「自定义源」条目并入用户当前接口配置的站点列表，原源与排序不变，不切换分组。
 * 探测或自检失败都不会改动注册表，用户看到的站点列表保持原样。
 */
public class AiSiteDialog extends BaseAlertDialog {

    private DialogAiSiteBinding binding;
    private SiteAdapter adapter;
    private Runnable callback;
    private boolean running;
    private boolean added;

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
        AiSite.reloadConfigs();
        refreshSites();
        Notify.show(R.string.ai_site_deleted);
        if (callback != null) callback.run();
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
        if (!Setting.hasFileAccess()) {
            Notify.show(R.string.setting_custom_csp_permission_required);
            return;
        }
        persistFields();
        added = false;
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

    /** 识别结束后收尾：本次真的加了站点才重载配置，避免无谓地重取用户当前配置。 */
    private void finishRecognize(final String message) {
        running = false;
        updateActions();
        if (added) AiSite.reloadConfigs();
        refreshSites();
        status(message);
        Notify.show(message);
        if (callback != null) callback.run();
    }

    // ---------------------------------------------------------------- 建站流水线（§12.3）

    /**
     * 四步建站：探测（本机，不耗 AI）→ 写源（AI）→ 自检（本机）→ 落盘注册。
     *
     * <p>自检不通过会把失败原因连同上一版源码回灌给 AI 修一轮；仍不通过就明确报「做不出来」，
     * 此时只留一个可被下次覆盖的源码文件，注册表保持不变，用户看到的站点列表不变。
     */
    private String recognize(final String target) {
        if (AiSite.looksLikeApi(target)) return done(addOne(probeSite(target)));

        status(R.string.ai_site_step_probe);
        AiSiteProbe.Result probe = AiSiteProbe.probe(target, this::status);
        if (probe == null) return ResUtil.getString(R.string.ai_site_add_fail, "");
        JSONObject shortcut = AiSite.fromHomepageHtml(target, probe.homeBody());
        if (shortcut != null) return done(addOne(shortcut));
        if (!probe.ok()) return ResUtil.getString(R.string.ai_site_probe_fail, probe.getError());
        if (!AiSiteSetting.canUseAi()) return ResUtil.getString(R.string.ai_site_need_ai);

        String samples = AiSiteProbe.toPrompt(probe);
        String url = AiSiteSetting.getUrl();
        String key = AiSiteSetting.getKey();
        String model = AiSiteSetting.getModel();

        status(R.string.ai_site_step_write);
        JSONObject written = AiSiteClient.writeSpider(target, samples, url, key, model, this::status);
        if (!written.optBoolean("ok")) return ResUtil.getString(R.string.ai_site_source_fail, written.optString("error"));

        String host = hostName(target);
        String id = AiSite.idOf("spider://" + host);
        String api = stage(id, written, 1);
        if (api.isEmpty()) return ResUtil.getString(R.string.ai_site_source_fail, "");

        status(R.string.ai_site_step_check);
        JSONObject tested = AiSiteSelfTest.verify(id, api, "{}", "", probe.isSearchable(), this::status);
        if (!tested.optBoolean("ok")) {
            status(R.string.ai_site_step_repair);
            JSONObject repaired = AiSiteClient.writeSpider(target, samples, url, key, model,
                    written.optString("source"), tested.optString("step") + "：" + tested.optString("error"), this::status);
            if (!repaired.optBoolean("ok")) return ResUtil.getString(R.string.ai_site_source_fail, repaired.optString("error"));
            String again = stage(id, repaired, 2);
            if (again.isEmpty()) return ResUtil.getString(R.string.ai_site_source_fail, "");
            tested = AiSiteSelfTest.verify(id + "#2", again, "{}", "", probe.isSearchable(), this::status);
            if (!tested.optBoolean("ok")) {
                return ResUtil.getString(R.string.ai_site_source_fail, tested.optString("step") + "：" + tested.optString("error"));
            }
            written = repaired;
            api = again;
        }

        JSONObject site = new JSONObject();
        try {
            site.put("id", id);
            site.put("name", host);
            site.put("api", api);
            site.put("type", AiSite.TYPE_SPIDER);
            site.put("searchable", probe.isSearchable() ? 1 : 0);
        } catch (Throwable e) {
            return ResUtil.getString(R.string.ai_site_source_fail, "");
        }
        String error = addOne(site);
        return error.isEmpty() ? ResUtil.getString(R.string.ai_site_source_ok, written.optString("lang")) : error;
    }

    /**
     * 落一个候选源码文件，返回它的本地接口地址；失败返回空串。
     *
     * <p>修正轮必须换文件名：加载器按站点 key 缓存 Spider，QuickJS 也按 URL 缓存模块正文，
     * 沿用同一地址会把上一版坏源码喂回自检。
     */
    private String stage(final String id, final JSONObject written, final int attempt) {
        String lang = AiSiteClient.LANG_JS.equals(written.optString("lang")) ? AiSiteClient.LANG_JS : AiSiteClient.LANG_PY;
        String name = attempt <= 1 ? "spider." + lang : "spider-" + attempt + "." + lang;
        return AiSite.stageSource(id, name, written.optString("source"));
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

    /** 在界面上留一行进度/结果。 */
    private void status(final int res) {
        status(ResUtil.getString(res));
    }

    private void status(final String text) {
        App.post(() -> {
            if (binding == null) return;
            binding.status.setText(text);
            binding.status.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        });
    }

    private String addOne(final JSONObject site) {
        if (site == null) return ResUtil.getString(R.string.ai_site_add_fail, "");
        String error = AiSite.addSite(App.get(), site);
        if (!error.isEmpty()) return ResUtil.getString(R.string.ai_site_add_fail, error);
        added = true;
        return "";
    }

    /** 把 {@link #addOne} 的结果补成一句可直接展示的文案。 */
    private String done(final String error) {
        return error.isEmpty() ? ResUtil.getString(R.string.ai_site_added_ok) : error;
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
