package com.silentcam.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** 갤럭시 카메라처럼 생긴 동그란 셔터 버튼 */
public class ShutterButton extends View {
    public static final int PHOTO = 0, VIDEO = 1, RECORDING = 2, RANGE = 3;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int state = PHOTO;
    private boolean pressed = false;
    private final float dp;

    public ShutterButton(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(4 * dp);
        ring.setColor(Color.WHITE);
    }

    public void setState(int s) { state = s; invalidate(); }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) { pressed = true; invalidate(); }
        else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { pressed = false; invalidate(); }
        return super.onTouchEvent(e);
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - 3 * dp;
        c.drawCircle(cx, cy, r, ring);
        float inner = r - 7 * dp;
        if (pressed) inner *= 0.88f;
        if (state == RECORDING) {
            fill.setColor(0xFFFF3B30);
            float s = inner * 0.5f;
            c.drawRoundRect(new RectF(cx - s, cy - s, cx + s, cy + s), 6 * dp, 6 * dp, fill);
        } else {
            fill.setColor(state == VIDEO ? 0xFFFF3B30 : (state == RANGE ? 0xFFFFD54F : Color.WHITE));
            c.drawCircle(cx, cy, inner, fill);
        }
    }
}
