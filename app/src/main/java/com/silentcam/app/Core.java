package com.silentcam.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;

/**
 * 안드로이드에 의존하지 않는 순수 자바 처리부.
 *  - 밝기 자동 보정(배경을 하얗게, 글자를 진하게)
 *  - 흑백(1비트) 변환
 *  - 페이지를 하나씩 바로 써 내려가는 PDF 작성기 (100장도 메모리 걱정 없음)
 */
public final class Core {
    private Core() {}

    /** ARGB 픽셀 → 회색(0~255) */
    public static int[] toGray(int[] argb) {
        int[] g = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            int r = (p >> 16) & 255, gr = (p >> 8) & 255, b = p & 255;
            g[i] = (r * 77 + gr * 150 + b * 29) >> 8;
        }
        return g;
    }

    /** 히스토그램으로 검은점·흰점을 찾아 늘려 줌. 종이 배경은 순백에 가까워져 용량도 줄어듦. */
    public static int[] levels(int[] gray) {
        int[] hist = new int[256];
        for (int v : gray) hist[v]++;
        int n = gray.length;
        int lo = percentile(hist, n, 0.01);
        int hi = percentile(hist, n, 0.80); // 문서는 대부분이 종이 배경
        if (hi - lo < 40) { lo = 0; hi = 255; }
        int[] lut = new int[256];
        for (int v = 0; v < 256; v++) {
            int o = (v - lo) * 255 / Math.max(1, hi - lo);
            lut[v] = o < 0 ? 0 : Math.min(255, o);
        }
        return lut;
    }

    private static int percentile(int[] hist, int n, double q) {
        long target = (long) (n * q), acc = 0;
        for (int v = 0; v < 256; v++) { acc += hist[v]; if (acc > target) return v; }
        return 255;
    }

    public static void applyLut(int[] gray, int[] lut) {
        for (int i = 0; i < gray.length; i++) gray[i] = lut[gray[i]];
    }

    /**
     * 적응형 흑백 변환(Bradley 방식). 조명이 고르지 않아도 글자만 검게 남김.
     * 결과: 한 줄씩 비트로 꽉 채운 배열 (1=흰색, 0=검정) — PDF 1비트 회색 이미지 형식 그대로.
     */
    public static byte[] binarize(int[] gray, int w, int h) {
        int[] integ = new int[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            int rowSum = 0;
            for (int x = 0; x < w; x++) {
                rowSum += gray[y * w + x];
                integ[(y + 1) * (w + 1) + x + 1] = integ[y * (w + 1) + x + 1] + rowSum;
            }
        }
        int s = Math.max(15, Math.max(w, h) / 20);
        int half = s / 2;
        final int T = 13; // 주변 평균보다 13% 이상 어두우면 글자
        int rowBytes = (w + 7) / 8;
        byte[] out = new byte[rowBytes * h];
        for (int y = 0; y < h; y++) {
            int y1 = Math.max(0, y - half), y2 = Math.min(h - 1, y + half);
            for (int x = 0; x < w; x++) {
                int x1 = Math.max(0, x - half), x2 = Math.min(w - 1, x + half);
                int count = (x2 - x1 + 1) * (y2 - y1 + 1);
                long sum = (long) integ[(y2 + 1) * (w + 1) + x2 + 1] - integ[y1 * (w + 1) + x2 + 1]
                        - integ[(y2 + 1) * (w + 1) + x1] + integ[y1 * (w + 1) + x1];
                int v = gray[y * w + x];
                boolean black = (long) v * count * 100 < sum * (100 - T) && v < 215;
                if (!black) out[y * rowBytes + (x >> 3)] |= (byte) (0x80 >> (x & 7));
            }
        }
        return out;
    }

    /** 넓은 범위의 주변 밝기(종이 배경 추정). 그림자·조명 얼룩 제거용 */
    private static int[] localMean(int[] gray, int w, int h, int s) {
        int[] integ = new int[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            int rowSum = 0;
            for (int x = 0; x < w; x++) {
                rowSum += gray[y * w + x];
                integ[(y + 1) * (w + 1) + x + 1] = integ[y * (w + 1) + x + 1] + rowSum;
            }
        }
        int half = s / 2;
        int[] m = new int[w * h];
        for (int y = 0; y < h; y++) {
            int y1 = Math.max(0, y - half), y2 = Math.min(h - 1, y + half);
            for (int x = 0; x < w; x++) {
                int x1 = Math.max(0, x - half), x2 = Math.min(w - 1, x + half);
                int count = (x2 - x1 + 1) * (y2 - y1 + 1);
                long sum = (long) integ[(y2 + 1) * (w + 1) + x2 + 1] - integ[y1 * (w + 1) + x2 + 1]
                        - integ[(y2 + 1) * (w + 1) + x1] + integ[y1 * (w + 1) + x1];
                m[y * w + x] = (int) (sum / count);
            }
        }
        return m;
    }

    /** 회색 모드: 배경을 고르게 하얗게, 글자는 진하게 */
    public static void flattenGray(int[] gray, int w, int h) {
        int[] m = localMean(gray, w, h, Math.max(31, Math.max(w, h) / 12));
        for (int i = 0; i < gray.length; i++) {
            int bg = Math.max(40, m[i] + 6);
            int v = gray[i] * 255 / bg;
            if (v > 225) v = 255;               // 종이 잡티 제거 → 용량 감소
            else v = v * v / 225;                // 글자 대비 강화
            gray[i] = Math.min(255, v);
        }
    }

    /** 컬러 모드: 색은 살리고 그림자·누런 조명만 보정 */
    public static void flattenColor(int[] argb, int w, int h) {
        int[] gray = toGray(argb);
        int[] m = localMean(gray, w, h, Math.max(31, Math.max(w, h) / 12));
        for (int i = 0; i < argb.length; i++) {
            int bg = Math.max(40, m[i] + 6);
            int p = argb[i];
            int r = Math.min(255, ((p >> 16) & 255) * 255 / bg);
            int g = Math.min(255, ((p >> 8) & 255) * 255 / bg);
            int b = Math.min(255, (p & 255) * 255 / bg);
            if (r > 228 && g > 228 && b > 228) { r = g = b = 255; }
            argb[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }

    public static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(1024, data.length / 8));
        byte[] buf = new byte[65536];
        while (!d.finished()) { int k = d.deflate(buf); bos.write(buf, 0, k); }
        d.end();
        return bos.toByteArray();
    }

    /** 페이지를 받는 즉시 파일에 써 내려가는 최소 PDF 작성기 */
    public static final class PdfWriter {
        private final OutputStream out;
        private long pos = 0;
        private final List<Long> offsets = new ArrayList<>(); // index = 객체번호-1
        private final List<Integer> pageIds = new ArrayList<>();
        private int nextId = 3; // 1=Catalog, 2=Pages

        public PdfWriter(OutputStream out) throws IOException {
            this.out = out;
            offsets.add(0L); offsets.add(0L);
            write("%PDF-1.4\n%âãÏÓ\n".getBytes(StandardCharsets.ISO_8859_1));
        }

        private void write(byte[] b) throws IOException { out.write(b); pos += b.length; }
        private void write(String s) throws IOException { write(s.getBytes(StandardCharsets.ISO_8859_1)); }

        private int begin(int id) throws IOException {
            while (offsets.size() < id) offsets.add(0L);
            offsets.set(id - 1, pos);
            write(id + " 0 obj\n");
            return id;
        }

        /** 1비트 흑백 이미지 페이지 */
        public void addBilevel(byte[] packedBits, int w, int h) throws IOException { addBilevel(packedBits, w, h, 0, 0); }

        /** pageW/pageH: 페이지 크기(pt). 0이면 A4 긴 변에 맞춤 */
        public void addBilevel(byte[] packedBits, int w, int h, double pageW, double pageH) throws IOException {
            byte[] z = deflate(packedBits);
            addImage(z, w, h, "/ColorSpace /DeviceGray /BitsPerComponent 1 /Filter /FlateDecode", pageW, pageH);
        }

        /** JPEG 그대로 넣는 페이지 (회색·컬러) */
        public void addJpeg(byte[] jpeg, int w, int h) throws IOException { addJpeg(jpeg, w, h, 0, 0); }

        public void addJpeg(byte[] jpeg, int w, int h, double pageW, double pageH) throws IOException {
            addImage(jpeg, w, h, "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode", pageW, pageH);
        }

        private void addImage(byte[] data, int w, int h, String dict, double pageW, double pageH) throws IOException {
            String pw, ph;
            if (pageW > 0 && pageH > 0) { pw = fmt(pageW); ph = fmt(pageH); }
            else {
                // 긴 변을 A4 긴 변(842pt)에 맞춤
                double scale = 842.0 / Math.max(w, h);
                pw = fmt(w * scale); ph = fmt(h * scale);
            }

            int img = begin(nextId++);
            write("<< /Type /XObject /Subtype /Image /Width " + w + " /Height " + h + " " + dict
                    + " /Length " + data.length + " >>\nstream\n");
            write(data);
            write("\nendstream\nendobj\n");

            byte[] content = ("q " + pw + " 0 0 " + ph + " 0 0 cm /Im0 Do Q").getBytes(StandardCharsets.ISO_8859_1);
            int cont = begin(nextId++);
            write("<< /Length " + content.length + " >>\nstream\n");
            write(content);
            write("\nendstream\nendobj\n");

            int page = begin(nextId++);
            write("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + pw + " " + ph + "] /Resources << /XObject << /Im0 "
                    + img + " 0 R >> >> /Contents " + cont + " 0 R >>\nendobj\n");
            pageIds.add(page);
        }

        public int pageCount() { return pageIds.size(); }

        public void finish() throws IOException {
            begin(2);
            StringBuilder kids = new StringBuilder();
            for (int id : pageIds) kids.append(id).append(" 0 R ");
            write("<< /Type /Pages /Kids [" + kids + "] /Count " + pageIds.size() + " >>\nendobj\n");
            begin(1);
            write("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
            long xref = pos;
            StringBuilder sb = new StringBuilder();
            sb.append("xref\n0 ").append(offsets.size() + 1).append("\n0000000000 65535 f \n");
            for (long off : offsets) sb.append(String.format(java.util.Locale.US, "%010d 00000 n \n", off));
            sb.append("trailer\n<< /Size ").append(offsets.size() + 1).append(" /Root 1 0 R >>\nstartxref\n")
              .append(xref).append("\n%%EOF\n");
            write(sb.toString());
            out.flush();
        }

        private static String fmt(double v) { return String.format(java.util.Locale.US, "%.2f", v); }
    }
}
