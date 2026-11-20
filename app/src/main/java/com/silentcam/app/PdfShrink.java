package com.silentcam.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 이미 있는 PDF의 용량 줄이기.
 * 각 쪽을 그림으로 다시 그린 뒤 압축해서 새 PDF로 저장한다(원본은 그대로 둠).
 */
public final class PdfShrink {
    private PdfShrink() { }

    /** 쪽 긴 변 픽셀 수 / JPEG 화질 (PdfBuild 단계와 같은 순서: 고화질·보통·저용량·흑백문서) */
    private static final int[] LONG_SIDE = {2000, 1600, 1300, 2200};
    private static final int[] QUALITY = {80, 62, 48, 0};

    public interface Progress { void on(int done, int total); }

    public static final class Result {
        public Uri uri;          // 줄인 파일 (줄지 않았으면 null)
        public String name;
        public long before, after;
        public int pages;
        public boolean notSmaller;
    }

    public static String displayName(ContentResolver cr, Uri u) {
        try (Cursor c = cr.query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (Exception ignored) { }
        String s = u.getLastPathSegment();
        return s == null ? "문서.pdf" : s;
    }

    public static long size(ContentResolver cr, Uri u) {
        try (Cursor c = cr.query(u, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) { }
        try (ParcelFileDescriptor p = cr.openFileDescriptor(u, "r")) {
            if (p != null) return p.getStatSize();
        } catch (Exception ignored) { }
        return -1;
    }

    public static Result shrink(Context ctx, Uri src, int level, Progress cb) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        Result res = new Result();
        String name = displayName(cr, src);
        res.before = size(cr, src);
        File tmp = File.createTempFile("shrink", ".pdf", ctx.getCacheDir());
        try {
            try (ParcelFileDescriptor pfd = cr.openFileDescriptor(src, "r")) {
                if (pfd == null) throw new IOException("파일을 열 수 없습니다");
                PdfRenderer r;
                try {
                    r = new PdfRenderer(pfd);
                } catch (SecurityException e) {
                    throw new IOException("암호가 걸린 PDF는 줄일 수 없습니다");
                }
                try (OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16)) {
                    Core.PdfWriter pw = new Core.PdfWriter(os);
                    int n = r.getPageCount();
                    for (int i = 0; i < n; i++) {
                        addPage(r, i, level, pw);
                        if (cb != null) cb.on(i + 1, n);
                    }
                    pw.finish();
                    res.pages = n;
                } finally {
                    r.close();
                }
            } catch (OutOfMemoryError e) {
                throw new IOException("메모리가 부족합니다");
            }
            res.after = tmp.length();
            if (res.before > 0 && res.after >= res.before) {
                res.notSmaller = true;
                res.name = name;
                return res;
            }
            String base = name.toLowerCase().endsWith(".pdf") ? name.substring(0, name.length() - 4) : name;
            res.name = base + "_압축.pdf";
            res.uri = save(cr, tmp, res.name);
            return res;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static void addPage(PdfRenderer r, int i, int level, Core.PdfWriter pw) throws IOException {
        try (PdfRenderer.Page p = r.openPage(i)) {
            double ptW = p.getWidth(), ptH = p.getHeight();
            double scale = LONG_SIDE[level] / Math.max(ptW, ptH);
            int w = Math.max(1, (int) Math.round(ptW * scale)), h = Math.max(1, (int) Math.round(ptH * scale));
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(Color.WHITE); // 투명 배경이 검게 나오지 않도록
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
            int[] px = new int[w * h];
            bmp.getPixels(px, 0, w, 0, 0, w, h);

            if (level == PdfBuild.BW) {
                bmp.recycle();
                pw.addBilevel(Core.binarize(Core.toGray(px), w, h), w, h, ptW, ptH);
                return;
            }
            if (level == PdfBuild.SMALL) {
                int[] gray = Core.toGray(px);
                for (int k = 0; k < px.length; k++) px[k] = 0xFF000000 | (gray[k] * 0x010101);
                bmp.setPixels(px, 0, w, 0, 0, w, h);
            }
            ByteArrayOutputStream jpg = new ByteArrayOutputStream(256 * 1024);
            bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY[level], jpg);
            bmp.recycle();
            pw.addJpeg(jpg.toByteArray(), w, h, ptW, ptH);
        }
    }

    private static Uri save(ContentResolver cr, File tmp, String name) throws IOException {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/무음카메라");
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new IOException("다운로드 폴더에 저장할 수 없습니다");
        try (InputStream in = new FileInputStream(tmp); OutputStream out = cr.openOutputStream(uri)) {
            if (out == null) throw new IOException("파일을 열 수 없습니다");
            byte[] buf = new byte[1 << 16];
            int k;
            while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
        } catch (IOException e) {
            cr.delete(uri, null, null);
            throw e;
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(uri, done, null, null);
        return uri;
    }
}
