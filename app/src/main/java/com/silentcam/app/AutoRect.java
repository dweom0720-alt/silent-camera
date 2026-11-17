package com.silentcam.app;

import android.graphics.Bitmap;
import android.graphics.RectF;

/**
 * 미리보기 화면에서 문서·모니터 화면을 찾아 그것을 감싸는 직사각형(0~1 비율)을 돌려준다.
 * 1) 밝은 덩어리(종이·켜진 화면) 찾기  2) 안 되면 길게 이어진 가로·세로 테두리선 찾기
 */
public final class AutoRect {
    private AutoRect() { }

    public static RectF detect(Bitmap src) {
        if (src == null || src.getWidth() < 8 || src.getHeight() < 8) return null;
        int w = 200;
        int h = Math.max(8, Math.round(src.getHeight() * (w / (float) src.getWidth())));
        Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        if (small != src) small.recycle();
        int[] g = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            g[i] = (((c >> 16) & 255) * 299 + ((c >> 8) & 255) * 587 + (c & 255) * 114) / 1000;
        }
        return detectGray(g, w, h);
    }

    static RectF detectGray(int[] g, int w, int h) {
        g = blur(g, w, h);
        RectF r = byRegion(g, w, h);
        if (r == null) r = byEdges(g, w, h);
        if (r == null) return null;
        float m = 0.006f; // 테두리가 잘리지 않게 살짝 여유
        r.set(Math.max(0, r.left - m), Math.max(0, r.top - m), Math.min(1, r.right + m), Math.min(1, r.bottom + m));
        return r;
    }

    private static int[] blur(int[] g, int w, int h) {
        int[] o = new int[g.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int s = 0, n = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w) continue;
                        s += g[yy * w + xx];
                        n++;
                    }
                }
                o[y * w + x] = s / n;
            }
        }
        return o;
    }

    private static int otsu(int[] g) {
        int[] hist = new int[256];
        for (int v : g) hist[v]++;
        long total = g.length, sum = 0;
        for (int i = 0; i < 256; i++) sum += (long) i * hist[i];
        long sumB = 0, wB = 0;
        double best = -1;
        int t = 128;
        for (int i = 0; i < 256; i++) {
            wB += hist[i];
            if (wB == 0) continue;
            long wF = total - wB;
            if (wF == 0) break;
            sumB += (long) i * hist[i];
            double mB = sumB / (double) wB, mF = (sum - sumB) / (double) wF;
            double between = (double) wB * wF * (mB - mF) * (mB - mF);
            if (between > best) { best = between; t = i; }
        }
        return t;
    }

    /** 화면 가운데가 속한 덩어리(밝은 종이·켜진 화면, 또는 어두운 화면)의 테두리 */
    private static RectF byRegion(int[] g, int w, int h) {
        int t = otsu(g);
        int cx = w / 2, cy = h / 2;
        int bright = 0, total = 0;
        for (int dy = -3; dy <= 3; dy++)
            for (int dx = -3; dx <= 3; dx++) { total++; if (g[(cy + dy) * w + cx + dx] > t) bright++; }
        boolean wantBright = bright * 2 >= total;
        boolean[] mask = new boolean[w * h];
        for (int i = 0; i < g.length; i++) mask[i] = (g[i] > t) == wantBright;

        // 가운데 바로 근처에서 출발
        int seed = -1;
        search:
        for (int rad = 0; rad <= 4; rad++) {
            for (int dy = -rad; dy <= rad; dy++) {
                for (int dx = -rad; dx <= rad; dx++) {
                    int i = (cy + dy) * w + (cx + dx);
                    if (mask[i]) { seed = i; break search; }
                }
            }
        }
        if (seed < 0) return null;

        boolean[] comp = new boolean[w * h];
        int[] stack = new int[w * h];
        int sp = 0, count = 0;
        stack[sp++] = seed;
        comp[seed] = true;
        int[] rows = new int[h], cols = new int[w];
        while (sp > 0) {
            int i = stack[--sp];
            count++;
            int x = i % w, y = i / w;
            rows[y]++;
            cols[x]++;
            if (x > 0 && mask[i - 1] && !comp[i - 1]) { comp[i - 1] = true; stack[sp++] = i - 1; }
            if (x < w - 1 && mask[i + 1] && !comp[i + 1]) { comp[i + 1] = true; stack[sp++] = i + 1; }
            if (y > 0 && mask[i - w] && !comp[i - w]) { comp[i - w] = true; stack[sp++] = i - w; }
            if (y < h - 1 && mask[i + w] && !comp[i + w]) { comp[i + w] = true; stack[sp++] = i + w; }
        }
        // 얇게 삐져나온 부분(책상 반사광 등)은 잘라냄
        int maxR = 0, maxC = 0;
        for (int v : rows) maxR = Math.max(maxR, v);
        for (int v : cols) maxC = Math.max(maxC, v);
        int top = 0, bottom = h - 1, left = 0, right = w - 1;
        while (top < h && rows[top] < maxR * 0.35f) top++;
        while (bottom > top && rows[bottom] < maxR * 0.35f) bottom--;
        while (left < w && cols[left] < maxC * 0.35f) left++;
        while (right > left && cols[right] < maxC * 0.35f) right--;
        int bw = right - left + 1, bh = bottom - top + 1;
        if (bw < 8 || bh < 8) return null;
        float areaFrac = bw * bh / (float) (w * h);
        if (areaFrac < 0.06f || areaFrac > 0.97f) return null;

        int inside = 0;
        long sumIn = 0, sumOut = 0;
        int nIn = 0, nOut = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                boolean in = x >= left && x <= right && y >= top && y <= bottom;
                if (in) { if (comp[i]) inside++; sumIn += g[i]; nIn++; }
                else { sumOut += g[i]; nOut++; }
            }
        }
        float fill = inside / (float) (bw * bh);
        if (fill < 0.6f) return null;
        if (nOut > 0 && Math.abs((sumIn / (double) nIn) - (sumOut / (double) nOut)) < 20) return null;
        boolean touchesAll = top <= 1 && left <= 1 && bottom >= h - 2 && right >= w - 2;
        if (touchesAll) return null;
        return new RectF(left / (float) w, top / (float) h, (right + 1) / (float) w, (bottom + 1) / (float) h);
    }

    /** 길게 이어진 가로선·세로선(문서 끝, 모니터 테두리)으로 찾기 */
    private static RectF byEdges(int[] g, int w, int h) {
        boolean[] he = new boolean[w * h], ve = new boolean[w * h];
        int[] gx = new int[w * h], gy = new int[w * h];
        int[] mags = new int[w * h];
        int n = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                int a = g[i - w - 1], b = g[i - w], c = g[i - w + 1];
                int d = g[i - 1], f = g[i + 1];
                int p = g[i + w - 1], q = g[i + w], r = g[i + w + 1];
                gx[i] = Math.abs((c + 2 * f + r) - (a + 2 * d + p));
                gy[i] = Math.abs((p + 2 * q + r) - (a + 2 * b + c));
                mags[n++] = Math.max(gx[i], gy[i]);
            }
        }
        int[] sorted = java.util.Arrays.copyOf(mags, n);
        java.util.Arrays.sort(sorted);
        int thr = Math.max(40, sorted[(int) (n * 0.88)]);
        for (int i = 0; i < w * h; i++) {
            he[i] = gy[i] > thr && gy[i] > gx[i];
            ve[i] = gx[i] > thr && gx[i] > gy[i];
        }
        int top = bestLine(he, w, h, true, 2, h / 2, true);
        int bottom = bestLine(he, w, h, true, h / 2, h - 2, false);
        int left = bestLine(ve, w, h, false, 2, w / 2, true);
        int right = bestLine(ve, w, h, false, w / 2, w - 2, false);
        int found = (top >= 0 ? 1 : 0) + (bottom >= 0 ? 1 : 0) + (left >= 0 ? 1 : 0) + (right >= 0 ? 1 : 0);
        if (found < 3) return null;
        if (top < 0) top = 0;
        if (bottom < 0) bottom = h - 1;
        if (left < 0) left = 0;
        if (right < 0) right = w - 1;
        if (right - left < w * 0.15f || bottom - top < h * 0.15f) return null;
        return new RectF(left / (float) w, top / (float) h, (right + 1) / (float) w, (bottom + 1) / (float) h);
    }

    /**
     * 범위 안에서 가장 길게 이어진 선의 위치. 길이가 비슷하면 바깥쪽 선을 고른다.
     * horizontal=true면 가로선(행), false면 세로선(열).
     */
    private static int bestLine(boolean[] e, int w, int h, boolean horizontal, int from, int to, boolean outerIsLow) {
        int len = horizontal ? w : h;
        int[] score = new int[Math.max(from, to) + 2];
        int max = 0;
        for (int k = from; k < to; k++) {
            int run = 0, best = 0, gap = 0;
            for (int j = 0; j < len; j++) {
                boolean on = false;
                for (int d = -1; d <= 1 && !on; d++) {
                    int kk = k + d;
                    if (kk < 0 || kk >= (horizontal ? h : w)) continue;
                    on = horizontal ? e[kk * w + j] : e[j * w + kk];
                }
                if (on) { run += 1 + gap; gap = 0; }
                else if (run > 0 && gap < 3) gap++;
                else { run = 0; gap = 0; }
                if (run > best) best = run;
            }
            score[k] = best;
            if (best > max) max = best;
        }
        if (max < len * 0.3f) return -1;
        float cut = max * 0.8f;
        if (outerIsLow) {
            for (int k = from; k < to; k++) if (score[k] >= cut) return k;
        } else {
            for (int k = to - 1; k >= from; k--) if (score[k] >= cut) return k;
        }
        return -1;
    }
}
