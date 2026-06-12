package com.pramod.octapadpromidi;

import android.content.Context;
import android.net.Uri;
import java.io.InputStream;
import java.io.OutputStream;

public class FileUtil {
    public static void copyUriToUri(Context context, Uri src, Uri dest) {
        try {
            InputStream in = context.getContentResolver().openInputStream(src);
            OutputStream out = context.getContentResolver().openOutputStream(dest);
            byte[] buf = new byte[1024];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            in.close();
            out.close();
        } catch (Exception e) { e.printStackTrace(); }
    }

    public static void copyRawToUri(Context context, int rawResId, Uri dest) {
        try {
            InputStream in = context.getResources().openRawResource(rawResId);
            OutputStream out = context.getContentResolver().openOutputStream(dest);
            byte[] buf = new byte[1024];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            in.close();
            out.close();
        } catch (Exception e) { e.printStackTrace(); }
    }
}

