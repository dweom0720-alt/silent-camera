package com.silentcam.app;

import android.content.Context;
import android.widget.FrameLayout;

/** 정해진 화면비(세로/가로)를 지키며 주어진 공간 안에 최대한 크게 들어가는 틀 */
public class AspectFrame extends FrameLayout {
    private float ratio = 4f / 3f; // 세로 ÷ 가로

    public AspectFrame(Context c) { super(c); }

    public void setRatio(float r) {
        if (r != ratio) { ratio = r; requestLayout(); }
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int W = MeasureSpec.getSize(wSpec), H = MeasureSpec.getSize(hSpec);
        int w = W, h = Math.round(W * ratio);
        if (h > H) { h = H; w = Math.round(H / ratio); }
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }
}
