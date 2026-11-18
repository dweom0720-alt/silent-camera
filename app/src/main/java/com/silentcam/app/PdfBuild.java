package com.silentcam.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/** 찍은 사진들 → 압축 PDF → '다운로드/무음카메라' 폴더에 저장 */
public final class PdfBuild {
    private PdfBuild() { }

    public static final int HIGH = 0, NORMAL = 1, SMALL = 2, BW = 3;
    public static final String[] NAMES = {"고화질", "보통", "저용량", "흑백문서"};
    public static final String[] DESC = {
            "컬러 · 선명함 우선 (용량 큼)",
            "컬러 · 화질과 용량 균형",
            "회색 · 용량 최소 (문서용)",
            "흑과 백 두 색 · 글자 문서 최소 용량"};
    private static final int[] LONG_SIDE = {2400, 1800, 1400, 2200};
    private static final int[] QUALITY = {82, 65, 50, 0};

    public interface Progress { void on(int done, int total); }

    public static final class Result { public Uri uri; public String name; public long bytes; public int pages; }

    public static Result make(Context ctx, List<Uri> photos, int level, boolean clean, String name, Progress cb)
            throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/무음카메라");
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new IOException("다운로드 폴더에 파일을 만들 수 없습니다");

        Counting counting;
        int pages = 0;
        try (OutputStream raw = cr.openOutputStream(uri)) {
            if (raw == null) throw new IOException("파일을 열 수 없습니다");
            counting = new Counting(new BufferedOutputStream(raw, 1 << 16));
            Core.PdfWriter pw = new Core.PdfWriter(counting);
            for (int i = 0; i < photos.size(); i++) {
                if (addPage(cr, pw, photos.get(i), level, clean)) pages++;
                if (cb != null) cb.on(i + 1, photos.size());
            }
            if (pages == 0) throw new IOException("읽을 수 있는 사진이 없습니다");
            pw.finish();
            counting.flush();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            cr.delete(uri, null, null);
            if (e instanceof OutOfMemoryError) throw new IOException("메모리가 부족합니다");
            throw e;
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(uri, done, null, null);

        Result r = new Result();
        r.uri = uri;
        r.name = name;
        r.bytes = counting.count;
        r.pages = pages;
        return r;
    }

    /** 사진 정보 없이 보여 줄 쪽당 예상 크기(KB): 일반 A4 문서 사진 기준 */
    public static final int[] TYPICAL_KB = {800, 300, 120, 60};
    /** PDF 한 쪽에 붙는 머리글 등 고정 크기(바이트) */
    private static final int PAGE_OVERHEAD = 320;

    private static final class Page { byte[] data; int w, h; boolean bilevel; }

    /** 사진 한 장을 압축 정도에 맞게 변환 (흑백문서는 압축 전 1비트 데이터) */
    private static Page encode(ContentResolver cr, Uri u, int level, boolean clean) throws IOException {
        Bitmap bmp = decode(cr, u, LONG_SIDE[level]);
        if (bmp == null) return null;
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        Page pg = new Page();
        pg.w = w; pg.h = h;

        if (level == BW) {
            bmp.recycle();
            pg.data = Core.binarize(Core.toGray(px), w, h);
            pg.bilevel = true;
            return pg;
        }
        if (level == SMALL) {
            int[] gray = Core.toGray(px);
            if (clean) Core.flattenGray(gray, w, h);
            for (int i = 0; i < px.length; i++) px[i] = 0xFF000000 | (gray[i] * 0x010101);
        } else if (clean) {
            Core.flattenColor(px, w, h);
        }
        Bitmap out = bmp.isMutable() ? bmp : bmp.copy(Bitmap.Config.ARGB_8888, true);
        if (out != bmp) bmp.recycle();
        out.setPixels(px, 0, w, 0, 0, w, h);
        ByteArrayOutputStream jpg = new ByteArrayOutputStream(256 * 1024);
        out.compress(Bitmap.CompressFormat.JPEG, QUALITY[level], jpg);
        out.recycle();
        pg.data = jpg.toByteArray();
        return pg;
    }

    private static boolean addPage(ContentResolver cr, Core.PdfWriter pw, Uri u, int level, boolean clean)
            throws IOException {
        Page pg = encode(cr, u, level, clean);
        if (pg == null) return false;
        if (pg.bilevel) pw.addBilevel(pg.data, pg.w, pg.h);
        else pw.addJpeg(pg.data, pg.w, pg.h);
        return true;
    }

    /** 고른 사진(최대 몇 장)을 실제로 압축해 보고 압축 정도별 쪽당 평균 크기(바이트)를 계산. 실패한 단계는 -1 */
    public static long[] estimatePerPage(ContentResolver cr, List<Uri> samples, boolean clean) {
        long[] r = new long[NAMES.length];
        for (int l = 0; l < NAMES.length; l++) {
            long sum = 0;
            int n = 0;
            for (Uri u : samples) {
                if (Thread.currentThread().isInterrupted()) return null;
                try {
                    Page pg = encode(cr, u, l, clean && l != BW);
                    if (pg == null) continue;
                    long b = pg.bilevel ? Core.deflate(pg.data).length : pg.data.length;
                    sum += b + PAGE_OVERHEAD;
                    n++;
                } catch (IOException | RuntimeException | OutOfMemoryError ignored) { }
            }
            r[l] = n == 0 ? -1 : sum / n;
        }
        return r;
    }

    /** 긴 변이 target 이하가 되도록 줄이고, 사진의 회전 정보대로 바로 세워서 읽기 */
    static Bitmap decode(ContentResolver cr, Uri u, int target) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = cr.openInputStream(u)) { BitmapFactory.decodeStream(in, null, o); }
        int longSide = Math.max(o.outWidth, o.outHeight);
        if (longSide <= 0) return null;
        int sample = 1;
        while (longSide / (sample * 2) >= target) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        o2.inMutable = true;
        Bitmap b;
        try (InputStream in = cr.openInputStream(u)) { b = BitmapFactory.decodeStream(in, null, o2); }
        if (b == null) return null;

        int deg = 0;
        try (InputStream in = cr.openInputStream(u)) {
            if (in != null) {
                int ori = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (ori == ExifInterface.ORIENTATION_ROTATE_90) deg = 90;
                else if (ori == ExifInterface.ORIENTATION_ROTATE_180) deg = 180;
                else if (ori == ExifInterface.ORIENTATION_ROTATE_270) deg = 270;
            }
        } catch (Exception ignored) { }

        int l = Math.max(b.getWidth(), b.getHeight());
        float s = l > target ? (float) target / l : 1f;
        if (s < 1f || deg != 0) {
            Matrix m = new Matrix();
            if (s < 1f) m.postScale(s, s);
            if (deg != 0) m.postRotate(deg);
            Bitmap t = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (t != b) b.recycle();
            if (t.isMutable()) b = t;
            else { b = t.copy(Bitmap.Config.ARGB_8888, true); t.recycle(); }
        }
        return b;
    }

    static final class Counting extends FilterOutputStream {
        long count;
        Counting(OutputStream o) { super(o); }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); count += len; }
    }
}
