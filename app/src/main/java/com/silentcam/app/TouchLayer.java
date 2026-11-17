package com.silentcam.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * 미리보기 위에 겹쳐 놓는 투명한 판.
 * - 한 번 톡: 초점 맞추기
 * - 두 손가락 벌리기/모으기: 확대·축소
 * - 좌우로 밀기: 촬영 모드 바꾸기
 * - 범위촬영 모드: 직사각형(가로·세로가 항상 수평·수직) 범위를 옮기고 크기 조절
 */
public class TouchLayer extends View {

    public interface Listener {
        void onTap(float x, float y);
        void onScale(float factor);
        void onSwipe(int dir); // -1 = 왼쪽으로 밀기, +1 = 오른쪽으로 밀기
        void onRectChanged(RectF normalized);
    }

    private static final int NONE = 0, MOVE = 1,
            TL = 2, TR = 3, BL = 4, BR = 5,
            LEFT = 6, TOP = 7, RIGHT = 8, BOTTOM = 9;

    private final float dp;
    private final Paint dim = new Paint();
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint();
    private final Paint focus = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scaler;
    private final int slop;

    private Listener listener;
    private boolean rectMode = false;
    private boolean locked = false;
    private boolean gridOn = false;
    private String sizeText = null;

    /** 범위(0~1 비율 좌표) */
    private final RectF nrect = new RectF(0.1f, 0.25f, 0.9f, 0.75f);

    private int drag = NONE;
    private boolean scaling = false;
    private boolean moved = false;
    private float downX, downY, lastX, lastY;
    private final RectF startPx = new RectF();

    private float focusX = -1, focusY = -1;
    private long focusAt = 0;

    public TouchLayer(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        dim.setColor(0x99000000);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * dp);
        border.setColor(Color.WHITE);
        handle.setStyle(Paint.Style.STROKE);
        handle.setStrokeWidth(5 * dp);
        handle.setStrokeCap(Paint.Cap.ROUND);
        handle.setColor(Color.WHITE);
        grid.setColor(0x66FFFFFF);
        grid.setStrokeWidth(1 * dp);
        focus.setStyle(Paint.Style.STROKE);
        focus.setStrokeWidth(2 * dp);
        focus.setColor(0xFFFFD54F);
        label.setColor(Color.WHITE);
        label.setTextSize(12 * dp);
        label.setShadowLayer(3 * dp, 0, 0, Color.BLACK);

        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                if (listener != null) listener.onScale(d.getScaleFactor());
                return true;
            }
        });
    }

    public void setListener(Listener l) { listener = l; }

    public void setRectMode(boolean on) { rectMode = on; invalidate(); }

    public boolean isRectMode() { return rectMode; }

    public void setLocked(boolean l) { locked = l; invalidate(); }

    public boolean isLocked() { return locked; }

    /** 손가락이 화면에 닿아 있는 동안 true */
    public boolean isTouching() { return touching; }
    private boolean touching = false;

    public void setGrid(boolean on) { gridOn = on; invalidate(); }

    public void setSizeText(String s) { sizeText = s; invalidate(); }

    public void setNormalizedRect(RectF r) {
        nrect.set(r);
        fixRect();
        invalidate();
    }

    public RectF getNormalizedRect() { return new RectF(nrect); }

    public void showFocus(float x, float y) {
        focusX = x;
        focusY = y;
        focusAt = SystemClock.uptimeMillis();
        invalidate();
    }

    private RectF px() {
        float w = getWidth(), h = getHeight();
        return new RectF(nrect.left * w, nrect.top * h, nrect.right * w, nrect.bottom * h);
    }

    private void fixRect() {
        float l = Math.min(nrect.left, nrect.right), r = Math.max(nrect.left, nrect.right);
        float t = Math.min(nrect.top, nrect.bottom), b = Math.max(nrect.top, nrect.bottom);
        nrect.set(Math.max(0, l), Math.max(0, t), Math.min(1, r), Math.min(1, b));
        if (nrect.width() < 0.05f || nrect.height() < 0.05f) nrect.set(0.1f, 0.25f, 0.9f, 0.75f);
    }

    private int hit(float x, float y) {
        RectF r = px();
        float t = 36 * dp;
        boolean nearL = Math.abs(x - r.left) < t, nearR = Math.abs(x - r.right) < t;
        boolean nearT = Math.abs(y - r.top) < t, nearB = Math.abs(y - r.bottom) < t;
        boolean inX = x > r.left - t && x < r.right + t;
        boolean inY = y > r.top - t && y < r.bottom + t;
        if (nearL && nearT) return TL;
        if (nearR && nearT) return TR;
        if (nearL && nearB) return BL;
        if (nearR && nearB) return BR;
        if (nearL && inY) return LEFT;
        if (nearR && inY) return RIGHT;
        if (nearT && inX) return TOP;
        if (nearB && inX) return BOTTOM;
        if (r.contains(x, y)) return MOVE;
        return NONE;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaler.onTouchEvent(e);
        int am = e.getActionMasked();
        touching = !(am == MotionEvent.ACTION_UP || am == MotionEvent.ACTION_CANCEL);
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = x;
                downY = lastY = y;
                moved = false;
                scaling = false;
                drag = (rectMode && !locked) ? hit(x, y) : NONE;
                startPx.set(px());
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                scaling = true;
                if (drag != NONE) { // 두 손가락이면 범위 조절 취소하고 확대·축소로
                    RectF s = startPx;
                    float w = getWidth(), h = getHeight();
                    nrect.set(s.left / w, s.top / h, s.right / w, s.bottom / h);
                    drag = NONE;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (scaling) return true;
                if (Math.abs(x - downX) > slop || Math.abs(y - downY) > slop) moved = true;
                if (drag != NONE && moved) {
                    applyDrag(x - downX, y - downY);
                    invalidate();
                }
                lastX = x;
                lastY = y;
                return true;
            case MotionEvent.ACTION_UP:
                if (!scaling) {
                    if (drag != NONE && moved) {
                        fixRect();
                        if (listener != null) listener.onRectChanged(getNormalizedRect());
                    } else if (!moved) {
                        if (listener != null) listener.onTap(x, y);
                    } else {
                        float dx = x - downX, dy = y - downY;
                        if (Math.abs(dx) > 80 * dp && Math.abs(dx) > Math.abs(dy) * 1.5f && listener != null) {
                            listener.onSwipe(dx < 0 ? -1 : 1);
                        }
                    }
                }
                drag = NONE;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                drag = NONE;
                return true;
        }
        return true;
    }

    private void applyDrag(float dx, float dy) {
        float w = getWidth(), h = getHeight();
        float min = 48 * dp;
        RectF s = startPx;
        float l = s.left, t = s.top, r = s.right, b = s.bottom;
        switch (drag) {
            case MOVE:
                float mdx = Math.max(-l, Math.min(w - r, dx));
                float mdy = Math.max(-t, Math.min(h - b, dy));
                l += mdx; r += mdx; t += mdy; b += mdy;
                break;
            case TL: l += dx; t += dy; break;
            case TR: r += dx; t += dy; break;
            case BL: l += dx; b += dy; break;
            case BR: r += dx; b += dy; break;
            case LEFT: l += dx; break;
            case RIGHT: r += dx; break;
            case TOP: t += dy; break;
            case BOTTOM: b += dy; break;
        }
        l = Math.max(0, Math.min(l, r - min));
        t = Math.max(0, Math.min(t, b - min));
        r = Math.min(w, Math.max(r, l + min));
        b = Math.min(h, Math.max(b, t + min));
        nrect.set(l / w, t / h, r / w, b / h);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (gridOn) {
            for (int i = 1; i < 3; i++) {
                c.drawLine(w * i / 3f, 0, w * i / 3f, h, grid);
                c.drawLine(0, h * i / 3f, w, h * i / 3f, grid);
            }
        }
        if (rectMode) {
            RectF r = px();
            c.drawRect(0, 0, w, r.top, dim);
            c.drawRect(0, r.bottom, w, h, dim);
            c.drawRect(0, r.top, r.left, r.bottom, dim);
            c.drawRect(r.right, r.top, w, r.bottom, dim);
            int col = locked ? 0xFFFFD54F : Color.WHITE;
            border.setColor(col);
            handle.setColor(col);
            c.drawRect(r, border);
            float k = Math.min(22 * dp, Math.min(r.width(), r.height()) / 3);
            // 네 모서리 ㄱ자 손잡이
            c.drawLine(r.left, r.top, r.left + k, r.top, handle);
            c.drawLine(r.left, r.top, r.left, r.top + k, handle);
            c.drawLine(r.right, r.top, r.right - k, r.top, handle);
            c.drawLine(r.right, r.top, r.right, r.top + k, handle);
            c.drawLine(r.left, r.bottom, r.left + k, r.bottom, handle);
            c.drawLine(r.left, r.bottom, r.left, r.bottom - k, handle);
            c.drawLine(r.right, r.bottom, r.right - k, r.bottom, handle);
            c.drawLine(r.right, r.bottom, r.right, r.bottom - k, handle);
            if (!locked) { // 변 가운데 손잡이
                float cx = r.centerX(), cy = r.centerY(), m = k / 2;
                c.drawLine(cx - m, r.top, cx + m, r.top, handle);
                c.drawLine(cx - m, r.bottom, cx + m, r.bottom, handle);
                c.drawLine(r.left, cy - m, r.left, cy + m, handle);
                c.drawLine(r.right, cy - m, r.right, cy + m, handle);
            }
            String txt = locked ? "범위 고정됨" : null;
            if (sizeText != null) txt = (txt == null ? "" : txt + " · ") + sizeText;
            if (txt != null) {
                float ty = r.top - 8 * dp;
                if (ty < 16 * dp) ty = r.top + 20 * dp;
                c.drawText(txt, r.left + 4 * dp, ty, label);
            }
        }
        long age = SystemClock.uptimeMillis() - focusAt;
        if (focusX >= 0 && age < 1200) {
            float rad = 36 * dp + (age < 200 ? (200 - age) / 200f * 16 * dp : 0);
            focus.setAlpha(age > 800 ? (int) (255 * (1200 - age) / 400f) : 255);
            c.drawCircle(focusX, focusY, rad, focus);
            postInvalidateOnAnimation();
        }
    }
}
