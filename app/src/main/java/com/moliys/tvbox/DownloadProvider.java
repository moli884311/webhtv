package com.moliys.tvbox;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 为「沫离下载」目录中的文件提供只读 content:// 访问，
 * 供第三方应用（播放器、解压、安装器等）打开，避免 file:// 暴露异常。
 */
public class DownloadProvider extends ContentProvider {

    /** authority 与包名绑定，避免正式版与测试版冲突。 */
    public static String authority(Context ctx) {
        return ctx.getPackageName() + ".dl";
    }

    public static Uri uriFor(Context ctx, File file) {
        Uri base = Uri.parse("content://" + authority(ctx) + "/dl/" + Uri.encode(file.getName()));
        return base.buildUpon()
                .appendQueryParameter("t", file.length() + "-" + file.lastModified())
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.length() == 0) {
            throw new FileNotFoundException("empty name");
        }
        File dir = DownloadHelper.dir();
        File target = new File(dir, name);
        String base = dir.getAbsolutePath() + File.separator;
        if (!target.getAbsolutePath().startsWith(base)) {
            throw new FileNotFoundException("outside download dir");
        }
        if (!target.exists()) {
            throw new FileNotFoundException("not found: " + name);
        }
        return target;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return DownloadHelper.mimeOf(uri.getLastPathSegment());
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
