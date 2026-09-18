package com.moliys.tvbox;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

public class ApkProvider extends ContentProvider {
    public static final String AUTHORITY = "com.moliys.tvbox.apk";

    public static File updateDir(Context ctx) {
        File dir = ctx.getExternalFilesDir("update");
        if (dir == null) {
            dir = new File(ctx.getFilesDir(), "update");
        }
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static Uri uriFor(File apk) {
        Uri base = Uri.parse("content://" + AUTHORITY + "/apk/" + Uri.encode(apk.getName()));
        return base.buildUpon()
                .appendQueryParameter("t", apk.length() + "-" + apk.lastModified())
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
        File dir = updateDir(getContext());
        File target = new File(dir, name);
        String base = dir.getAbsolutePath() + File.separator;
        if (!target.getAbsolutePath().startsWith(base)) {
            throw new FileNotFoundException("outside update dir");
        }
        return target;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
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
