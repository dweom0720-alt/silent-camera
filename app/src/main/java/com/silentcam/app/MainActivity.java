package com.silentcam.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ViewPort;
import androidx.camera.core.ZoomState;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 무음 카메라.
 * 사진 · 동영상 · 범위촬영(직사각형 범위만 잘라 저장) 세 가지 모드.
 * 셔터 소리를 전혀 내지 않습니다.
 */
public class MainActivity extends AppCompatActivity {

    private static final int MODE_VIDEO = 0, MODE_PHOTO = 1, MODE_RANGE = 2;
    private static final String[] MODE_NAMES = {"동영상", "사진", "범위촬영"};
    private static final String[] RATIO_NAMES = {"3:4", "9:16", "1:1"};
    private static final float[] RATIOS = {4f / 3f, 16f / 9f, 1f};
    private static final String[] FLASH_NAMES = {"플래시 끔", "플래시 자동", "플래시 켬"};
    private static final int[] TIMERS = {0, 3, 10};
    private static final String PHOTO_DIR = "DCIM/Camera";
    private static final String RANGE_DIR = "DCIM/범위촬영";

    private float dp;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    // 화면 요소
    private FrameLayout root;
    private AspectFrame frame;
    private PreviewView previewView;
    private TouchLayer touch;
    private View blink;
    private TextView countdown, recTime, toastText, zoomText;
    private TextView flashBtn, timerBtn, ratioBtn, gridBtn, lockBtn, resetBtn;
    private LinearLayout zoomRow, modeRow;
    private ImageView thumb;
    private ShutterButton shutter;
    private TextView flipBtn;
    private final List<View> rotatables = new ArrayList<>();

    // 카메라
    private ProcessCameraProvider provider;
    private Camera camera;
    private Preview preview;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private long recStart;

    // 상태
    private int mode = MODE_PHOTO;
    private boolean front = false;
    private int ratioIdx = 0;
    private int flashIdx = 0;
    private int timerIdx = 0;
    private boolean torch = false;
    private int surfaceRotation = Surface.ROTATION_0;
    private int uiDegrees = 0;
    private boolean counting = false;
    private int lastW = 0, lastH = 0;
    private Uri lastUri;
    private String lastMime = "image/jpeg";
    private OrientationEventListener orientation;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("cam", MODE_PRIVATE);
        mode = prefs.getInt("mode", MODE_PHOTO);
        ratioIdx = prefs.getInt("ratio", 0);
        flashIdx = prefs.getInt("flash", 0);
        timerIdx = prefs.getInt("timer", 0);
        String lu = prefs.getString("lastUri", null);
        if (lu != null) { lastUri = Uri.parse(lu); lastMime = prefs.getString("lastMime", "image/jpeg"); }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        setContentView(root);

        orientation = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int o) {
                if (o == ORIENTATION_UNKNOWN) return;
                int rot, deg;
                if (o >= 45 && o < 135) { rot = Surface.ROTATION_270; deg = -90; }
                else if (o >= 135 && o < 225) { rot = Surface.ROTATION_180; deg = 180; }
                else if (o >= 225 && o < 315) { rot = Surface.ROTATION_90; deg = 90; }
                else { rot = Surface.ROTATION_0; deg = 0; }
                if (rot != surfaceRotation) {
                    surfaceRotation = rot;
                    if (imageCapture != null && mode == MODE_PHOTO) imageCapture.setTargetRotation(rot);
                    if (videoCapture != null && recording == null) videoCapture.setTargetRotation(rot);
                    rotateIcons(deg);
                }
            }
        };

        if (hasCam()) startCamera();
        else ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, 1);
    }

    private boolean hasCam() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasMic() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (hasCam()) startCamera();
        else Toast.makeText(this, "카메라 권한이 있어야 촬영할 수 있습니다", Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (orientation.canDetectOrientation()) orientation.enable();
        loadThumb();
    }

    @Override
    protected void onPause() {
        super.onPause();
        orientation.disable();
        if (recording != null) stopRecording();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdown();
    }

    // ───────────────────────── 화면 만들기 ─────────────────────────

    private int px(float v) { return Math.round(v * dp); }

    private TextView chip(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(10), px(6), px(10), px(6));
        return t;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // 미리보기 틀
        frame = new AspectFrame(this);
        frame.setRatio(mode == MODE_VIDEO ? RATIOS[1] : RATIOS[ratioIdx]);
        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        frame.addView(previewView, new FrameLayout.LayoutParams(-1, -1));
        blink = new View(this);
        blink.setBackgroundColor(Color.BLACK);
        blink.setAlpha(0f);
        frame.addView(blink, new FrameLayout.LayoutParams(-1, -1));
        touch = new TouchLayer(this);
        touch.setGrid(prefs.getBoolean("grid", false));
        touch.setLocked(prefs.getBoolean("lock", false));
        touch.setNormalizedRect(new RectF(
                prefs.getFloat("rl", 0.1f), prefs.getFloat("rt", 0.25f),
                prefs.getFloat("rr", 0.9f), prefs.getFloat("rb", 0.75f)));
        frame.addView(touch, new FrameLayout.LayoutParams(-1, -1));
        countdown = new TextView(this);
        countdown.setTextColor(Color.WHITE);
        countdown.setTextSize(96);
        countdown.setTypeface(Typeface.DEFAULT_BOLD);
        countdown.setShadowLayer(8, 0, 0, Color.BLACK);
        countdown.setVisibility(View.GONE);
        frame.addView(countdown, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        zoomText = new TextView(this);
        zoomText.setTextColor(Color.WHITE);
        zoomText.setTextSize(16);
        zoomText.setShadowLayer(6, 0, 0, Color.BLACK);
        zoomText.setVisibility(View.GONE);
        FrameLayout.LayoutParams zl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        zl.topMargin = px(40);
        frame.addView(zoomText, zl);

        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(-1, -1, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        fl.topMargin = px(52);
        root.addView(frame, fl);

        previewView.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
            int w = r - l, h = bt - t;
            if (w > 0 && h > 0 && (w != lastW || h != lastH)) {
                lastW = w; lastH = h;
                if (provider != null) bindCamera();
            }
        });

        // 위쪽 버튼 줄
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(px(6), 0, px(6), 0);
        flashBtn = chip("");
        timerBtn = chip("");
        ratioBtn = chip("");
        gridBtn = chip("격자");
        lockBtn = chip("🔓 범위");
        resetBtn = chip("↺ 범위");
        for (TextView t : new TextView[]{flashBtn, timerBtn, ratioBtn, gridBtn, lockBtn, resetBtn}) {
            top.addView(t, new LinearLayout.LayoutParams(0, -1, 1));
            rotatables.add(t);
        }
        root.addView(top, new FrameLayout.LayoutParams(-1, px(52), Gravity.TOP));

        flashBtn.setOnClickListener(v -> {
            if (mode == MODE_VIDEO) {
                torch = !torch;
                if (camera != null) camera.getCameraControl().enableTorch(torch);
            } else {
                flashIdx = (flashIdx + 1) % 3;
                prefs.edit().putInt("flash", flashIdx).apply();
                if (imageCapture != null) imageCapture.setFlashMode(flashMode());
            }
            refreshTop();
        });
        timerBtn.setOnClickListener(v -> {
            timerIdx = (timerIdx + 1) % TIMERS.length;
            prefs.edit().putInt("timer", timerIdx).apply();
            refreshTop();
        });
        ratioBtn.setOnClickListener(v -> {
            if (mode == MODE_VIDEO) return;
            ratioIdx = (ratioIdx + 1) % RATIOS.length;
            prefs.edit().putInt("ratio", ratioIdx).apply();
            frame.setRatio(RATIOS[ratioIdx]);
            lastW = 0;
            refreshTop();
            bindCamera();
        });
        gridBtn.setOnClickListener(v -> {
            boolean g = !prefs.getBoolean("grid", false);
            prefs.edit().putBoolean("grid", g).apply();
            touch.setGrid(g);
            refreshTop();
        });
        lockBtn.setOnClickListener(v -> {
            boolean l = !touch.isLocked();
            touch.setLocked(l);
            prefs.edit().putBoolean("lock", l).apply();
            refreshTop();
            showToast(l ? "범위가 고정되었습니다" : "범위를 움직일 수 있습니다");
        });
        resetBtn.setOnClickListener(v -> {
            RectF r = new RectF(0.1f, 0.25f, 0.9f, 0.75f);
            touch.setNormalizedRect(r);
            saveRect(r);
        });

        // 녹화 시간
        recTime = new TextView(this);
        recTime.setTextColor(Color.WHITE);
        recTime.setTextSize(15);
        recTime.setPadding(px(10), px(3), px(10), px(3));
        GradientDrawable rb = new GradientDrawable();
        rb.setColor(0xFFFF3B30);
        rb.setCornerRadius(px(12));
        recTime.setBackground(rb);
        recTime.setVisibility(View.GONE);
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        rl.topMargin = px(62);
        root.addView(recTime, rl);

        // 짧은 안내 문구
        toastText = new TextView(this);
        toastText.setTextColor(Color.WHITE);
        toastText.setTextSize(14);
        toastText.setPadding(px(14), px(6), px(14), px(6));
        GradientDrawable tb = new GradientDrawable();
        tb.setColor(0xAA000000);
        tb.setCornerRadius(px(16));
        toastText.setBackground(tb);
        toastText.setVisibility(View.GONE);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        root.addView(toastText, tl);

        // 아래쪽 영역
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(0x55000000);
        bottom.setPadding(0, px(6), 0, px(18));

        zoomRow = new LinearLayout(this);
        zoomRow.setGravity(Gravity.CENTER);
        bottom.addView(zoomRow, new LinearLayout.LayoutParams(-1, px(48)));

        modeRow = new LinearLayout(this);
        modeRow.setGravity(Gravity.CENTER);
        for (int i = 0; i < MODE_NAMES.length; i++) {
            TextView t = chip(MODE_NAMES[i]);
            t.setTextSize(15);
            t.setPadding(px(16), px(8), px(16), px(8));
            final int m = i;
            t.setOnClickListener(v -> setMode(m));
            modeRow.addView(t);
        }
        bottom.addView(modeRow, new LinearLayout.LayoutParams(-1, px(44)));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable tbg = new GradientDrawable();
        tbg.setShape(GradientDrawable.OVAL);
        tbg.setColor(0xFF333333);
        tbg.setStroke(px(2), Color.WHITE);
        thumb.setBackground(tbg);
        thumb.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
        });
        thumb.setClipToOutline(true);
        thumb.setOnClickListener(v -> openLast());
        rotatables.add(thumb);

        shutter = new ShutterButton(this);
        shutter.setOnClickListener(v -> onShutter());

        flipBtn = chip("⟲");
        flipBtn.setTextSize(26);
        GradientDrawable fbg = new GradientDrawable();
        fbg.setShape(GradientDrawable.OVAL);
        fbg.setColor(0x44FFFFFF);
        flipBtn.setBackground(fbg);
        flipBtn.setOnClickListener(v -> {
            if (recording != null) return;
            front = !front;
            bindCamera();
        });
        rotatables.add(flipBtn);

        controls.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        controls.addView(thumb, new LinearLayout.LayoutParams(px(52), px(52)));
        controls.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        controls.addView(shutter, new LinearLayout.LayoutParams(px(80), px(80)));
        controls.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        controls.addView(flipBtn, new LinearLayout.LayoutParams(px(52), px(52)));
        controls.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        bottom.addView(controls, new LinearLayout.LayoutParams(-1, px(90)));

        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        touch.setListener(new TouchLayer.Listener() {
            @Override
            public void onTap(float x, float y) { focusAt(x, y); }

            @Override
            public void onScale(float f) {
                if (camera == null) return;
                ZoomState z = camera.getCameraInfo().getZoomState().getValue();
                if (z == null) return;
                setZoom(z.getZoomRatio() * f);
            }

            @Override
            public void onSwipe(int dir) {
                if (recording != null) return;
                int m = mode + (dir < 0 ? 1 : -1);
                if (m >= 0 && m < MODE_NAMES.length) setMode(m);
            }

            @Override
            public void onRectChanged(RectF n) { saveRect(n); }
        });

        refreshMode();
        refreshTop();
    }

    private void saveRect(RectF r) {
        prefs.edit().putFloat("rl", r.left).putFloat("rt", r.top)
                .putFloat("rr", r.right).putFloat("rb", r.bottom).apply();
    }

    private void rotateIcons(int deg) {
        uiDegrees = deg;
        for (View v : rotatables) v.animate().rotation(deg).setDuration(250).start();
    }

    private void refreshTop() {
        if (mode == MODE_VIDEO) flashBtn.setText(torch ? "조명 켬" : "조명 끔");
        else flashBtn.setText(FLASH_NAMES[flashIdx]);
        timerBtn.setText(TIMERS[timerIdx] == 0 ? "타이머 끔" : "타이머 " + TIMERS[timerIdx] + "초");
        ratioBtn.setText(mode == MODE_VIDEO ? "9:16" : RATIO_NAMES[ratioIdx]);
        ratioBtn.setAlpha(mode == MODE_VIDEO ? 0.4f : 1f);
        boolean g = prefs.getBoolean("grid", false);
        gridBtn.setTextColor(g ? 0xFFFFD54F : Color.WHITE);
        boolean range = mode == MODE_RANGE;
        lockBtn.setVisibility(range ? View.VISIBLE : View.GONE);
        resetBtn.setVisibility(range ? View.VISIBLE : View.GONE);
        lockBtn.setText(touch.isLocked() ? "🔒 고정" : "🔓 범위");
        lockBtn.setTextColor(touch.isLocked() ? 0xFFFFD54F : Color.WHITE);
    }

    private void refreshMode() {
        for (int i = 0; i < modeRow.getChildCount(); i++) {
            TextView t = (TextView) modeRow.getChildAt(i);
            boolean on = i == mode;
            t.setTextColor(on ? 0xFFFFD54F : Color.WHITE);
            t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        touch.setRectMode(mode == MODE_RANGE);
        shutter.setState(mode == MODE_VIDEO ? ShutterButton.VIDEO
                : mode == MODE_RANGE ? ShutterButton.RANGE : ShutterButton.PHOTO);
    }

    private void setMode(int m) {
        if (m == mode || recording != null || counting) return;
        boolean videoChange = (m == MODE_VIDEO) != (mode == MODE_VIDEO);
        mode = m;
        prefs.edit().putInt("mode", m).apply();
        if (m != MODE_VIDEO && torch) { torch = false; }
        refreshMode();
        refreshTop();
        frame.setRatio(m == MODE_VIDEO ? RATIOS[1] : RATIOS[ratioIdx]);
        if (videoChange || RATIOS[ratioIdx] != RATIOS[1]) lastW = 0;
        bindCamera();
    }

    private void showToast(String s) {
        toastText.setText(s);
        toastText.setVisibility(View.VISIBLE);
        toastText.setAlpha(1f);
        toastText.animate().cancel();
        toastText.animate().alpha(0f).setStartDelay(900).setDuration(400)
                .withEndAction(() -> toastText.setVisibility(View.GONE)).start();
    }

    // ───────────────────────── 카메라 ─────────────────────────

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try {
                provider = f.get();
                bindCamera();
            } catch (Exception e) {
                Toast.makeText(this, "카메라를 열 수 없습니다: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private int flashMode() {
        return flashIdx == 1 ? ImageCapture.FLASH_MODE_AUTO
                : flashIdx == 2 ? ImageCapture.FLASH_MODE_ON : ImageCapture.FLASH_MODE_OFF;
    }

    private void bindCamera() {
        if (provider == null) return;
        if (previewView.getWidth() == 0 || previewView.getHeight() == 0) {
            previewView.post(this::bindCamera);
            return;
        }
        provider.unbindAll();
        camera = null;
        imageCapture = null;
        videoCapture = null;

        CameraSelector sel = front ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
        boolean wide = mode == MODE_VIDEO || ratioIdx == 1;
        AspectRatioStrategy ars = wide ? AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                : AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY;

        preview = new Preview.Builder()
                .setResolutionSelector(new ResolutionSelector.Builder().setAspectRatioStrategy(ars).build())
                .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        try {
            if (mode == MODE_VIDEO) {
                Recorder rec = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.fromOrderedList(
                                Arrays.asList(Quality.UHD, Quality.FHD, Quality.HD),
                                FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)))
                        .build();
                videoCapture = VideoCapture.withOutput(rec);
                videoCapture.setTargetRotation(surfaceRotation);
                camera = provider.bindToLifecycle(this, sel, preview, videoCapture);
                if (torch) camera.getCameraControl().enableTorch(true);
            } else {
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setResolutionSelector(new ResolutionSelector.Builder()
                                .setAspectRatioStrategy(ars)
                                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                                .build())
                        .setFlashMode(flashMode())
                        // 범위촬영은 화면 그대로(세로 기준)로 받아서 범위를 정확히 자른 뒤 돌린다
                        .setTargetRotation(mode == MODE_RANGE ? Surface.ROTATION_0 : surfaceRotation)
                        .build();
                ViewPort vp = previewView.getViewPort(Surface.ROTATION_0);
                UseCaseGroup.Builder g = new UseCaseGroup.Builder().addUseCase(preview).addUseCase(imageCapture);
                if (vp != null) g.setViewPort(vp);
                camera = provider.bindToLifecycle(this, sel, g.build());
            }
        } catch (Exception e) {
            Toast.makeText(this, "카메라 연결 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        camera.getCameraInfo().getZoomState().removeObservers(this);
        buildZoomButtons();
        camera.getCameraInfo().getZoomState().observe(this, z -> highlightZoom(z.getZoomRatio()));
    }

    private final List<Float> zoomSteps = new ArrayList<>();

    private void buildZoomButtons() {
        zoomRow.removeAllViews();
        zoomSteps.clear();
        if (camera == null) return;
        ZoomState z = camera.getCameraInfo().getZoomState().getValue();
        float min = z != null ? z.getMinZoomRatio() : 1f;
        float max = z != null ? z.getMaxZoomRatio() : 1f;
        if (min < 0.95f) zoomSteps.add(min);
        zoomSteps.add(1f);
        for (float s : new float[]{2f, 3f, 10f}) if (max >= s) zoomSteps.add(s);
        for (float s : zoomSteps) {
            TextView t = chip(fmtZoom(s));
            t.setTextSize(12);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(0x66000000);
            t.setBackground(bg);
            t.setOnClickListener(v -> setZoom(s));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(40), px(40));
            lp.leftMargin = lp.rightMargin = px(6);
            zoomRow.addView(t, lp);
        }
    }

    private String fmtZoom(float s) {
        if (Math.abs(s - Math.round(s)) < 0.05f) return Math.round(s) + "";
        return String.format(Locale.US, "%.1f", s).replaceFirst("^0", "");
    }

    private void highlightZoom(float cur) {
        int best = -1;
        for (int i = 0; i < zoomSteps.size(); i++) {
            float s = zoomSteps.get(i);
            float next = i + 1 < zoomSteps.size() ? zoomSteps.get(i + 1) : Float.MAX_VALUE;
            if (cur >= s - 0.01f && cur < next - 0.01f) best = i;
        }
        for (int i = 0; i < zoomRow.getChildCount(); i++) {
            TextView t = (TextView) zoomRow.getChildAt(i);
            boolean on = i == best;
            t.setTextColor(on ? 0xFFFFD54F : Color.WHITE);
            t.setText(on && Math.abs(cur - zoomSteps.get(i)) > 0.05f
                    ? String.format(Locale.US, "%.1fx", cur) : fmtZoom(zoomSteps.get(i)) + (on ? "x" : ""));
        }
    }

    private void setZoom(float r) {
        if (camera == null) return;
        ZoomState z = camera.getCameraInfo().getZoomState().getValue();
        if (z != null) r = Math.max(z.getMinZoomRatio(), Math.min(z.getMaxZoomRatio(), r));
        camera.getCameraControl().setZoomRatio(r);
        zoomText.setText(String.format(Locale.US, "%.1fx", r));
        zoomText.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideZoom);
        ui.postDelayed(hideZoom, 1000);
    }

    private final Runnable hideZoom = () -> zoomText.setVisibility(View.GONE);

    private void focusAt(float x, float y) {
        if (camera == null) return;
        touch.showFocus(x, y);
        MeteringPoint p = previewView.getMeteringPointFactory().createPoint(x, y);
        FocusMeteringAction a = new FocusMeteringAction.Builder(p,
                FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(4, TimeUnit.SECONDS).build();
        camera.getCameraControl().startFocusAndMetering(a);
    }

    // ───────────────────────── 촬영 ─────────────────────────

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP
                || code == KeyEvent.KEYCODE_CAMERA) {
            if (e.getRepeatCount() == 0) onShutter();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    private void onShutter() {
        if (mode == MODE_VIDEO && recording != null) { stopRecording(); return; }
        if (counting) return;
        int sec = TIMERS[timerIdx];
        if (sec == 0) { fire(); return; }
        counting = true;
        countdown.setVisibility(View.VISIBLE);
        tick(sec);
    }

    private void tick(int left) {
        if (left == 0) {
            countdown.setVisibility(View.GONE);
            counting = false;
            fire();
            return;
        }
        countdown.setText(String.valueOf(left));
        ui.postDelayed(() -> tick(left - 1), 1000);
    }

    private void fire() {
        if (mode == MODE_VIDEO) startRecording();
        else if (mode == MODE_RANGE) takeRange();
        else takePhoto();
    }

    /** 소리 대신 화면 깜빡임 + 짧은 진동으로 촬영 알림 */
    private void feedback() {
        shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        blink.animate().cancel();
        blink.setAlpha(0.7f);
        blink.animate().alpha(0f).setDuration(180).start();
    }

    private String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
    }

    private void takePhoto() {
        if (imageCapture == null) return;
        feedback();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.Media.DISPLAY_NAME, stamp() + ".jpg");
        cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        cv.put(MediaStore.Images.Media.RELATIVE_PATH, PHOTO_DIR);
        ImageCapture.OutputFileOptions.Builder ob = new ImageCapture.OutputFileOptions.Builder(
                getContentResolver(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
        ImageCapture.Metadata md = new ImageCapture.Metadata();
        md.setReversedHorizontal(front);
        ob.setMetadata(md);
        imageCapture.takePicture(ob.build(), worker, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults r) {
                Uri u = r.getSavedUri();
                if (u != null) ui.post(() -> setLast(u, "image/jpeg"));
            }

            @Override
            public void onError(@NonNull ImageCaptureException e) {
                ui.post(() -> showToast("저장 실패: " + e.getMessage()));
            }
        });
    }

    /** 범위촬영: 고화질로 찍은 뒤 직사각형 범위만 잘라서 저장 */
    private void takeRange() {
        if (imageCapture == null) return;
        feedback();
        final RectF n = touch.getNormalizedRect();
        final boolean mirror = front;
        final int extra = surfaceDegrees(surfaceRotation);
        imageCapture.takePicture(worker, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                try {
                    Uri u = cropAndSave(image, n, mirror, extra);
                    ui.post(() -> {
                        if (u != null) { setLast(u, "image/jpeg"); showToast("범위 저장됨"); }
                        else showToast("저장 실패");
                    });
                } catch (Throwable t) {
                    ui.post(() -> showToast("저장 실패: " + t.getMessage()));
                } finally {
                    image.close();
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException e) {
                ui.post(() -> showToast("촬영 실패: " + e.getMessage()));
            }
        });
    }

    private static int surfaceDegrees(int rot) {
        switch (rot) {
            case Surface.ROTATION_90: return 90;
            case Surface.ROTATION_180: return 180;
            case Surface.ROTATION_270: return 270;
            default: return 0;
        }
    }

    @SuppressWarnings("deprecation")
    private Uri cropAndSave(ImageProxy image, RectF n, boolean mirror, int deviceDeg) throws Exception {
        ByteBuffer buf = image.getPlanes()[0].getBuffer();
        buf.rewind();
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        int rot = image.getImageInfo().getRotationDegrees();
        Rect crop = image.getCropRect();

        BitmapRegionDecoder dec = BitmapRegionDecoder.newInstance(bytes, 0, bytes.length, false);
        int bw = dec.getWidth(), bh = dec.getHeight();
        if (crop == null || crop.isEmpty() || crop.right > bw || crop.bottom > bh) crop = new Rect(0, 0, bw, bh);

        // 화면 기준(바로 선) 범위 → 센서 원본 기준 범위로 되돌리기
        float u0 = n.left, u1 = n.right, v0 = n.top, v1 = n.bottom;
        if (mirror) { float a = 1 - u1, b = 1 - u0; u0 = a; u1 = b; }
        float[] xs = new float[2], ys = new float[2];
        float[][] pts = {{u0, v0}, {u1, v1}};
        for (int i = 0; i < 2; i++) {
            float u = pts[i][0], v = pts[i][1], x, y;
            switch (rot) {
                case 90: x = v; y = 1 - u; break;
                case 180: x = 1 - u; y = 1 - v; break;
                case 270: x = 1 - v; y = u; break;
                default: x = u; y = v;
            }
            xs[i] = x; ys[i] = y;
        }
        int cw = crop.width(), ch = crop.height();
        Rect region = new Rect(
                crop.left + Math.round(Math.min(xs[0], xs[1]) * cw),
                crop.top + Math.round(Math.min(ys[0], ys[1]) * ch),
                crop.left + Math.round(Math.max(xs[0], xs[1]) * cw),
                crop.top + Math.round(Math.max(ys[0], ys[1]) * ch));
        region.intersect(0, 0, bw, bh);
        if (region.width() < 2 || region.height() < 2) { dec.recycle(); return null; }

        Bitmap part = dec.decodeRegion(region, null);
        dec.recycle();

        // 폰을 가로로 들고 찍었으면 글자가 바로 서도록 추가로 돌림
        int total = rot + (mirror ? deviceDeg : (360 - deviceDeg));
        total = ((total % 360) + 360) % 360;
        Matrix m = new Matrix();
        if (total != 0) m.postRotate(total);
        if (mirror) m.postScale(-1, 1);
        Bitmap out = (total != 0 || mirror)
                ? Bitmap.createBitmap(part, 0, 0, part.getWidth(), part.getHeight(), m, true) : part;
        if (out != part) part.recycle();

        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.Media.DISPLAY_NAME, "RANGE_" + stamp() + ".jpg");
        cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        cv.put(MediaStore.Images.Media.RELATIVE_PATH, RANGE_DIR);
        cv.put(MediaStore.Images.Media.IS_PENDING, 1);
        ContentResolver cr = getContentResolver();
        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) { out.recycle(); return null; }
        try (OutputStream os = cr.openOutputStream(uri)) {
            out.compress(Bitmap.CompressFormat.JPEG, 95, os);
        }
        out.recycle();
        ContentValues done = new ContentValues();
        done.put(MediaStore.Images.Media.IS_PENDING, 0);
        cr.update(uri, done, null, null);
        return uri;
    }

    @SuppressLint("MissingPermission")
    private void startRecording() {
        if (videoCapture == null) return;
        feedback();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Video.Media.DISPLAY_NAME, stamp() + ".mp4");
        cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        cv.put(MediaStore.Video.Media.RELATIVE_PATH, PHOTO_DIR);
        MediaStoreOutputOptions opts = new MediaStoreOutputOptions.Builder(getContentResolver(),
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(cv).build();
        videoCapture.setTargetRotation(surfaceRotation);
        PendingRecording pr = videoCapture.getOutput().prepareRecording(this, opts);
        if (hasMic()) pr = pr.withAudioEnabled();
        recStart = System.currentTimeMillis();
        recording = pr.start(ContextCompat.getMainExecutor(this), ev -> {
            if (ev instanceof VideoRecordEvent.Status) {
                long s = ((VideoRecordEvent.Status) ev).getRecordingStats().getRecordedDurationNanos() / 1_000_000_000L;
                recTime.setText(String.format(Locale.US, "● %02d:%02d", s / 60, s % 60));
            } else if (ev instanceof VideoRecordEvent.Finalize) {
                VideoRecordEvent.Finalize f = (VideoRecordEvent.Finalize) ev;
                if (!f.hasError() || f.getError() == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) {
                    Uri u = f.getOutputResults().getOutputUri();
                    if (u != null && !Uri.EMPTY.equals(u)) setLast(u, "video/mp4");
                } else {
                    showToast("녹화 저장 실패");
                }
            }
        });
        shutter.setState(ShutterButton.RECORDING);
        recTime.setText("● 00:00");
        recTime.setVisibility(View.VISIBLE);
        modeRow.setAlpha(0.3f);
        flipBtn.setAlpha(0.3f);
    }

    private void stopRecording() {
        if (recording != null) { recording.stop(); recording = null; }
        feedback();
        shutter.setState(ShutterButton.VIDEO);
        recTime.setVisibility(View.GONE);
        modeRow.setAlpha(1f);
        flipBtn.setAlpha(1f);
    }

    // ───────────────────────── 갤러리 썸네일 ─────────────────────────

    private void setLast(Uri u, String mime) {
        lastUri = u;
        lastMime = mime;
        prefs.edit().putString("lastUri", u.toString()).putString("lastMime", mime).apply();
        loadThumb();
    }

    private void loadThumb() {
        final Uri u = lastUri;
        if (u == null) return;
        worker.execute(() -> {
            try {
                Bitmap b = getContentResolver().loadThumbnail(u, new Size(px(64), px(64)), null);
                ui.post(() -> thumb.setImageBitmap(b));
            } catch (Exception e) {
                ui.post(() -> thumb.setImageDrawable(null));
            }
        });
    }

    private void openLast() {
        if (lastUri == null) { showToast("아직 찍은 사진이 없습니다"); return; }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(lastUri, lastMime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            showToast("갤러리를 열 수 없습니다");
        }
    }
}
