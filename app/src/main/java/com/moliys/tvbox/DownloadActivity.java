package com.moliys.tvbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 应用内置的下载目录页：直接列出 Download/沫离下载 下的文件，
 * 点击即可用系统应用打开；支持「管理」多选删除，解决下载后找不到文件的问题。
 */
public class DownloadActivity extends Activity {

    private static final int BG = 0xFF0F1115;
    private static final int CARD = 0xFF1A1E27;
    private static final int TEXT = 0xFFE8ECF3;
    private static final int SUB = 0xFF8A93A6;
    private static final int ACCENT = 0xFF4C8DFF;
    private static final int DANGER = 0xFFFF5A5F;

    private LinearLayout listBox;
    private TextView pathView;
    private TextView emptyView;
    private TextView grantBtn;
    private TextView actionBtn;
    private TextView deleteBtn;

    private boolean selecting;
    private final LinkedHashSet<File> selected = new LinkedHashSet<File>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, dp(8));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView back = new TextView(this);
        back.setText("‹  返回");
        back.setTextColor(ACCENT);
        back.setTextSize(16f);
        back.setPadding(0, dp(4), dp(16), dp(4));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackPressed();
            }
        });
        bar.addView(back);

        TextView title = new TextView(this);
        title.setText("下载目录");
        title.setTextColor(TEXT);
        title.setTextSize(19f);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(title, titleLp);

        deleteBtn = new TextView(this);
        deleteBtn.setText("删除(0)");
        deleteBtn.setTextColor(DANGER);
        deleteBtn.setTextSize(15f);
        deleteBtn.setPadding(dp(10), dp(6), dp(10), dp(6));
        deleteBtn.setVisibility(View.GONE);
        deleteBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDelete();
            }
        });
        bar.addView(deleteBtn);

        actionBtn = new TextView(this);
        actionBtn.setText("管理");
        actionBtn.setTextColor(ACCENT);
        actionBtn.setTextSize(15f);
        actionBtn.setPadding(dp(12), dp(6), 0, dp(6));
        actionBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSelecting();
            }
        });
        bar.addView(actionBtn);

        TextView refresh = new TextView(this);
        refresh.setText("刷新");
        refresh.setTextColor(ACCENT);
        refresh.setTextSize(15f);
        refresh.setPadding(dp(12), dp(6), 0, dp(6));
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
            }
        });
        bar.addView(refresh);

        pathView = new TextView(this);
        pathView.setTextColor(SUB);
        pathView.setTextSize(12f);
        LinearLayout.LayoutParams pathLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pathLp.topMargin = dp(6);
        root.addView(pathView, pathLp);

        grantBtn = new TextView(this);
        grantBtn.setText("需要存储权限才能查看已下载文件，点此授权");
        grantBtn.setTextColor(Color.WHITE);
        grantBtn.setTextSize(14f);
        grantBtn.setGravity(Gravity.CENTER);
        grantBtn.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable gb = new GradientDrawable();
        gb.setColor(ACCENT);
        gb.setCornerRadius(dp(10));
        grantBtn.setBackground(gb);
        LinearLayout.LayoutParams grantLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        grantLp.topMargin = dp(12);
        grantBtn.setLayoutParams(grantLp);
        grantBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAllFilesSettings();
            }
        });
        root.addView(grantBtn);

        ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.topMargin = dp(12);
        root.addView(scroll, scrollLp);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listBox, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        emptyView = new TextView(this);
        emptyView.setTextColor(SUB);
        emptyView.setTextSize(14f);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(12), dp(40), dp(12), dp(12));
        listBox.addView(emptyView);

        TextView tip = new TextView(this);
        tip.setText("提示：文件保存于系统「下载/沫离下载」目录；长按文件也可进入管理删除。");
        tip.setTextColor(SUB);
        tip.setTextSize(12f);
        tip.setPadding(0, dp(10), 0, dp(4));
        root.addView(tip);

        setContentView(root);
    }

    private boolean hasAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return true;
    }

    private void openAllFilesSettings() {
        if (Build.VERSION.SDK_INT < 30) {
            Toast.makeText(this, "请在系统设置中允许本应用访问存储", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        } catch (Throwable ignored) {
        }
    }

    private void toggleSelecting() {
        selecting = !selecting;
        selected.clear();
        refresh();
    }

    private void exitSelecting() {
        selecting = false;
        selected.clear();
        refresh();
    }

    private void updateBar() {
        if (selecting) {
            actionBtn.setText("取消");
            deleteBtn.setVisibility(View.VISIBLE);
            deleteBtn.setText("删除(" + selected.size() + ")");
        } else {
            actionBtn.setText("管理");
            deleteBtn.setVisibility(View.GONE);
        }
    }

    private void refresh() {
        DownloadHelper.ensureDir();
        pathView.setText(DownloadHelper.dirPath());
        boolean access = hasAccess();
        grantBtn.setVisibility(access ? View.GONE : View.VISIBLE);
        updateBar();

        listBox.removeAllViews();
        List<File> files = DownloadHelper.listFiles();
        if (!access) {
            emptyView.setText("尚未获得存储权限，无法读取下载目录。");
            listBox.addView(emptyView);
            return;
        }
        if (files.isEmpty()) {
            selecting = false;
            actionBtn.setText("管理");
            deleteBtn.setVisibility(View.GONE);
            emptyView.setText("暂无下载文件");
            listBox.addView(emptyView);
            return;
        }
        selected.retainAll(files);
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        for (File f : files) {
            listBox.addView(buildRow(f, fmt));
        }
    }

    private View buildRow(final File f, SimpleDateFormat fmt) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(10));
        row.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);

        final CheckBox cb = new CheckBox(this);
        cb.setClickable(false);
        cb.setFocusable(false);
        cb.setChecked(selected.contains(f));
        cb.setVisibility(selecting ? View.VISIBLE : View.GONE);
        row.addView(cb);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(col, colLp);

        TextView name = new TextView(this);
        name.setText(f.getName());
        name.setTextColor(TEXT);
        name.setTextSize(15f);
        col.addView(name);

        TextView meta = new TextView(this);
        meta.setText(fmt.format(new Date(f.lastModified())) + "   " + humanSize(f.length()));
        meta.setTextColor(SUB);
        meta.setTextSize(12f);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = dp(3);
        col.addView(meta, metaLp);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selecting) {
                    if (!selected.remove(f)) {
                        selected.add(f);
                    }
                    cb.setChecked(selected.contains(f));
                    updateBar();
                } else {
                    openFile(f);
                }
            }
        });
        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (!selecting) {
                    selecting = true;
                    selected.clear();
                }
                if (!selected.remove(f)) {
                    selected.add(f);
                }
                refresh();
                return true;
            }
        });

        return row;
    }

    private void confirmDelete() {
        final int n = selected.size();
        if (n == 0) {
            Toast.makeText(this, "请先选择要删除的文件", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("删除文件")
                .setMessage("确定删除选中的 " + n + " 个文件？删除后不可恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        doDelete();
                    }
                })
                .show();
    }

    private void doDelete() {
        int ok = 0;
        for (File f : selected) {
            try {
                if (f.delete()) {
                    ok++;
                }
            } catch (Throwable ignored) {
            }
        }
        selected.clear();
        selecting = false;
        refresh();
        Toast.makeText(this, "已删除 " + ok + " 个文件", Toast.LENGTH_SHORT).show();
    }

    private static String humanSize(long n) {
        if (n < 1024) {
            return n + " B";
        }
        if (n < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", n / 1024.0);
        }
        if (n < 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", n / 1024.0 / 1024.0);
        }
        return String.format(Locale.US, "%.2f GB", n / 1024.0 / 1024.0 / 1024.0);
    }

    private void openFile(File f) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(DownloadProvider.uriFor(this, f), DownloadHelper.mimeOf(f.getName()));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        try {
            Intent chooser = new Intent(Intent.ACTION_VIEW);
            chooser.setDataAndType(DownloadProvider.uriFor(this, f), "*/*");
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(Intent.createChooser(chooser, "打开文件"));
            return;
        } catch (Throwable ignored) {
        }
        Toast.makeText(this, "没有可打开该文件的应用", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        if (selecting) {
            exitSelecting();
            return;
        }
        finish();
    }
}
