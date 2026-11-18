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
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.content.res.Configuration;
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
import androidx.camera.extensions.ExtensionMode;
import androidx.camera.extensions.ExtensionsManager;
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
    private LinearLayout zoomRow, modeRow, topBar, bottom, controls;
    private View[] spacers;
    private boolean wide = false;
    // 미세 줌 막대
    private LinearLayout zoomPanel;
    private SeekBar zoomBar;
    private TextView zoomLabel;
    private boolean zoomDragging = false;
    // 자동 범위 맞춤
    private TextView autoBtn;
    private boolean autoTrack = false;
    private boolean autoBusy = false;
    // 셔터 위치 옮기기
    private View shutterSlot;
    private boolean shutterDragging = false;
    private ImageView thumb;
    private ShutterButton shutter;
    private TextView flipBtn;
    private final List<View> rotatables = new ArrayList<>();

    // 카메라
    private ProcessCameraProvider provider;
    private ExtensionsManager extensions;
    // 화질 보정: 삼성 카메라 내부 처리(자동·HDR·야간) 사용
    private static final int[] EXT_MODES = {ExtensionMode.AUTO, ExtensionMode.HDR, ExtensionMode.NIGHT, ExtensionMode.NONE};
    private static final String[] EXT_NAMES = {"화질 자동", "화질 HDR", "화질 야간", "보정 끔"};
    private int extIdx = 0;
    private int extActive = ExtensionMode.NONE;
    private boolean extBroken = false;
    private TextView extBtn;
    private TextView pdfBtn;
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
        extIdx = prefs.getInt("ext", 0);
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
        t.setTextSize(12);
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

        autoBtn = chip("⌖ 자동맞춤");
        autoBtn.setTextSize(13);
        GradientDrawable ab = new GradientDrawable();
        ab.setColor(0x99000000);
        ab.setCornerRadius(px(18));
        autoBtn.setBackground(ab);
        autoBtn.setPadding(px(12), px(8), px(12), px(8));
        frame.addView(autoBtn, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT));
        autoBtn.setOnClickListener(v -> {
            if (autoTrack) { setAutoTrack(false); return; }
            if (touch.isLocked()) { showToast("범위 고정을 먼저 풀어주세요"); return; }
            autoFitOnce(true);
        });
        autoBtn.setOnLongClickListener(v -> {
            if (touch.isLocked()) { showToast("범위 고정을 먼저 풀어주세요"); return true; }
            setAutoTrack(!autoTrack);
            return true;
        });

        root.addView(frame, new FrameLayout.LayoutParams(-1, -1));

        previewView.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
            int w = r - l, h = bt - t;
            if (w > 0 && h > 0 && (w != lastW || h != lastH)) {
                lastW = w; lastH = h;
                if (provider != null) bindCamera();
            }
        });

        // 위쪽 버튼 줄
        LinearLayout top = new LinearLayout(this);
        topBar = top;
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(px(6), 0, px(6), 0);
        flashBtn = chip("");
        timerBtn = chip("");
        ratioBtn = chip("");
        gridBtn = chip("격자");
        extBtn = chip("");
        lockBtn = chip("🔓 범위");
        pdfBtn = chip("PDF");
        resetBtn = chip("↺ 범위");
        for (TextView t : new TextView[]{extBtn, flashBtn, timerBtn, ratioBtn, gridBtn, lockBtn, resetBtn, pdfBtn}) {
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
        extBtn.setOnClickListener(v -> {
            if (mode == MODE_VIDEO || extensions == null) return;
            CameraSelector base = front ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
            for (int k = 1; k <= EXT_MODES.length; k++) { // 이 폰에서 되는 다음 모드로
                int n = (extIdx + k) % EXT_MODES.length;
                if (EXT_MODES[n] == ExtensionMode.NONE || extensions.isExtensionAvailable(base, EXT_MODES[n])) {
                    extIdx = n;
                    break;
                }
            }
            prefs.edit().putInt("ext", extIdx).apply();
            extBroken = false;
            bindCamera();
        });
        pdfBtn.setOnClickListener(v -> openPdf());
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
            root.post(this::centerFrame);
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
            if (l && autoTrack) setAutoTrack(false);
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
        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(0x55000000);
        bottom.setPadding(0, px(6), 0, px(18));

        zoomRow = new LinearLayout(this);
        zoomRow.setGravity(Gravity.CENTER);
        bottom.addView(zoomRow);

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
        bottom.addView(modeRow);

        controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
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
        thumb.setOnLongClickListener(v -> { openPdf(); return true; });
        rotatables.add(thumb);

        shutter = new ShutterButton(this);


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

        spacers = new View[]{new View(this), new View(this), new View(this), new View(this)};
        controls.addView(spacers[0]);
        controls.addView(thumb, new LinearLayout.LayoutParams(px(52), px(52)));
        controls.addView(spacers[1]);
        shutterSlot = new View(this);
        controls.addView(shutterSlot, new LinearLayout.LayoutParams(px(80), px(80)));
        controls.addView(spacers[2]);
        controls.addView(flipBtn, new LinearLayout.LayoutParams(px(52), px(52)));
        controls.addView(spacers[3]);
        bottom.addView(controls);

        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        // 셔터는 맨 위에 떠 있고, 길게 눌러 원하는 곳으로 옮길 수 있음
        root.addView(shutter, new FrameLayout.LayoutParams(px(80), px(80), Gravity.TOP | Gravity.LEFT));
        setupShutterDrag();
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> root.post(this::placeShutter));
        buildZoomPanel();
        applyLayout();

        touch.setListener(new TouchLayer.Listener() {
            @Override
            public void onTap(float x, float y) { focusAt(x, y); }

            @Override
            public void onScale(float f) {
                if (camera == null) return;
                ZoomState z = camera.getCameraInfo().getZoomState().getValue();
                if (z == null) return;
                setZoom(z.getZoomRatio() * f);
                if (zoomPanel.getVisibility() == View.VISIBLE) keepZoomPanel();
            }

            @Override
            public void onSwipe(int dir) {
                if (recording != null) return;
                int m = mode + (dir < 0 ? 1 : -1);
                if (m >= 0 && m < MODE_NAMES.length) setMode(m);
            }

            @Override
            public void onRectChanged(RectF n) {
                saveRect(n);
                if (autoTrack) setAutoTrack(false); // 손으로 고치면 자동 추적은 멈춤
            }
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
        if (mode == MODE_VIDEO) { extBtn.setText("보정 -"); extBtn.setAlpha(0.4f); }
        else if (extensions == null) { extBtn.setText("보정 확인중"); extBtn.setAlpha(0.4f); }
        else if (extActive == ExtensionMode.NONE) {
            extBtn.setText(EXT_MODES[extIdx] == ExtensionMode.NONE ? "보정 끔" : "보정 미지원");
            extBtn.setAlpha(1f);
        } else {
            extBtn.setText(EXT_NAMES[extIdx]);
            extBtn.setAlpha(1f);
        }
        extBtn.setTextColor(extActive != ExtensionMode.NONE ? 0xFFFFD54F : Color.WHITE);
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
        autoBtn.setVisibility(mode == MODE_RANGE ? View.VISIBLE : View.GONE);
        if (mode != MODE_RANGE && autoTrack) setAutoTrack(false);
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
        root.post(this::centerFrame);
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
                ListenableFuture<ExtensionsManager> ef = ExtensionsManager.getInstanceAsync(this, provider);
                ef.addListener(() -> {
                    try { extensions = ef.get(); } catch (Exception ignored) { extensions = null; }
                    bindCamera();
                }, ContextCompat.getMainExecutor(this));
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
        extActive = ExtensionMode.NONE;
        if (mode != MODE_VIDEO && extensions != null && !extBroken) {
            int want = EXT_MODES[extIdx];
            int[] tryList = want == ExtensionMode.NONE ? new int[0]
                    : new int[]{want, ExtensionMode.AUTO, ExtensionMode.HDR};
            for (int em : tryList) {
                try {
                    if (extensions.isExtensionAvailable(sel, em)) {
                        sel = extensions.getExtensionEnabledCameraSelector(sel, em);
                        extActive = em;
                        break;
                    }
                } catch (Exception ignored) { }
            }
        }
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
                        .setJpegQuality(100)
                        // 범위촬영은 화면 그대로(세로 기준)로 받아서 범위를 정확히 자른 뒤 돌린다
                        .setTargetRotation(mode == MODE_RANGE ? Surface.ROTATION_0 : surfaceRotation)
                        .build();
                ViewPort vp = previewView.getViewPort(Surface.ROTATION_0);
                UseCaseGroup.Builder g = new UseCaseGroup.Builder().addUseCase(preview).addUseCase(imageCapture);
                if (vp != null) g.setViewPort(vp);
                camera = provider.bindToLifecycle(this, sel, g.build());
            }
        } catch (Exception e) {
            if (extActive != ExtensionMode.NONE) { // 보정 모드로 안 열리면 보정 없이 다시
                extBroken = true;
                bindCamera();
                return;
            }
            Toast.makeText(this, "카메라 연결 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        refreshTop();
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
            t.setOnClickListener(v -> {
                ZoomState cz = camera != null ? camera.getCameraInfo().getZoomState().getValue() : null;
                // 지금 선택된 배율을 한 번 더 누르면 미세 조절 막대 열기
                if (cz != null && Math.abs(cz.getZoomRatio() - s) < 0.05f) showZoomPanel();
                else setZoom(s);
            });
            t.setOnLongClickListener(v -> { showZoomPanel(); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(40), px(40));
            if (wide) lp.topMargin = lp.bottomMargin = px(5);
            else lp.leftMargin = lp.rightMargin = px(6);
            zoomRow.addView(t, lp);
        }
    }

    private String fmtZoom(float s) {
        if (Math.abs(s - Math.round(s)) < 0.05f) return Math.round(s) + "";
        return String.format(Locale.US, "%.1f", s).replaceFirst("^0", "");
    }

    private void highlightZoom(float cur) {
        syncZoomBar(cur);
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

    // ───────────── 자동 범위 맞춤 ─────────────

    private void setAutoTrack(boolean on) {
        autoTrack = on;
        autoBtn.setText(on ? "⌖ 자동추적 중" : "⌖ 자동맞춤");
        autoBtn.setTextColor(on ? 0xFFFFD54F : Color.WHITE);
        ui.removeCallbacks(trackTick);
        if (on) { showToast("문서·화면을 계속 따라갑니다 (누르면 멈춤)"); ui.post(trackTick); }
    }

    private final Runnable trackTick = new Runnable() {
        @Override
        public void run() {
            if (!autoTrack || mode != MODE_RANGE) return;
            if (!touch.isTouching() && !counting && !touch.isLocked()) autoFitOnce(false);
            ui.postDelayed(this, 500);
        }
    };

    /** 지금 미리보기에서 문서·모니터 화면을 찾아 범위를 맞춤 */
    private void autoFitOnce(boolean manual) {
        if (autoBusy) return;
        Bitmap b = previewView.getBitmap();
        if (b == null) { if (manual) showToast("화면 준비 중입니다"); return; }
        autoBusy = true;
        worker.execute(() -> {
            RectF r = null;
            try { r = AutoRect.detect(b); } catch (Throwable ignored) { }
            b.recycle();
            final RectF found = r;
            ui.post(() -> {
                autoBusy = false;
                if (mode != MODE_RANGE || touch.isLocked() || touch.isTouching()) return;
                if (found == null) { if (manual) showToast("문서·화면을 찾지 못했습니다. 조금 떨어져 다시 해보세요"); return; }
                RectF cur = touch.getNormalizedRect();
                RectF next = found;
                if (!manual) { // 계속 따라갈 때는 부드럽게
                    float k = 0.5f;
                    next = new RectF(cur.left + (found.left - cur.left) * k, cur.top + (found.top - cur.top) * k,
                            cur.right + (found.right - cur.right) * k, cur.bottom + (found.bottom - cur.bottom) * k);
                }
                touch.setNormalizedRect(next);
                saveRect(next);
                if (manual) showToast("범위를 맞췄습니다 (길게 누르면 계속 따라감)");
            });
        });
    }

    // ───────────── 셔터 위치 옮기기 ─────────────

    private String shutterKey() { return wide ? "shW" : "shN"; }

    /** 셔터를 저장된 위치(없으면 원래 자리)에 놓기 */
    private void placeShutter() {
        if (shutterDragging || root.getWidth() == 0) return;
        float cx, cy;
        float sx = prefs.getFloat(shutterKey() + "x", -1), sy = prefs.getFloat(shutterKey() + "y", -1);
        if (sx >= 0 && sy >= 0) {
            cx = sx * root.getWidth();
            cy = sy * root.getHeight();
        } else {
            float[] c = slotCenter();
            cx = c[0];
            cy = c[1];
        }
        float half = px(40);
        shutter.setTranslationX(clamp(cx, half, root.getWidth() - half) - half);
        shutter.setTranslationY(clamp(cy, half, root.getHeight() - half) - half);
    }

    private float[] slotCenter() {
        int[] a = new int[2], b = new int[2];
        shutterSlot.getLocationInWindow(a);
        root.getLocationInWindow(b);
        return new float[]{a[0] - b[0] + shutterSlot.getWidth() / 2f, a[1] - b[1] + shutterSlot.getHeight() / 2f};
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    @SuppressLint("ClickableViewAccessibility")
    private void setupShutterDrag() {
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        final float[] down = new float[4]; // rawX, rawY, transX, transY
        final boolean[] moved = {false};
        final Runnable longPress = () -> {
            shutterDragging = true;
            shutter.setPressedLook(false);
            shutter.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            shutter.animate().scaleX(1.15f).scaleY(1.15f).setDuration(120).start();
            showToast("원하는 곳으로 끌어 놓으세요 (원래 자리에 놓으면 복귀)");
        };
        shutter.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX(); down[1] = e.getRawY();
                    down[2] = shutter.getTranslationX(); down[3] = shutter.getTranslationY();
                    moved[0] = false;
                    shutter.setPressedLook(true);
                    ui.postDelayed(longPress, 450);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - down[0], dy = e.getRawY() - down[1];
                    if (Math.abs(dx) > slop || Math.abs(dy) > slop) moved[0] = true;
                    if (shutterDragging) {
                        float half = px(40);
                        shutter.setTranslationX(clamp(down[2] + dx, 0, root.getWidth() - 2 * half));
                        shutter.setTranslationY(clamp(down[3] + dy, 0, root.getHeight() - 2 * half));
                    } else if (moved[0]) {
                        ui.removeCallbacks(longPress);
                        shutter.setPressedLook(false);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    ui.removeCallbacks(longPress);
                    shutter.setPressedLook(false);
                    if (shutterDragging) {
                        shutterDragging = false;
                        shutter.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                        float half = px(40);
                        float cx = shutter.getTranslationX() + half, cy = shutter.getTranslationY() + half;
                        float[] slot = slotCenter();
                        if (Math.hypot(cx - slot[0], cy - slot[1]) < px(60)) {
                            prefs.edit().remove(shutterKey() + "x").remove(shutterKey() + "y").apply();
                            showToast("셔터를 원래 자리로 돌렸습니다");
                        } else {
                            prefs.edit().putFloat(shutterKey() + "x", cx / root.getWidth())
                                    .putFloat(shutterKey() + "y", cy / root.getHeight()).apply();
                        }
                        placeShutter();
                    } else if (!moved[0]) {
                        onShutter();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    ui.removeCallbacks(longPress);
                    shutter.setPressedLook(false);
                    if (shutterDragging) { shutterDragging = false; shutter.setScaleX(1f); shutter.setScaleY(1f); placeShutter(); }
                    return true;
            }
            return false;
        });
    }

    // ───────────── 펼친 화면(폴드 안쪽 화면) 배치 ─────────────

    @Override
    public void onConfigurationChanged(@NonNull Configuration c) {
        super.onConfigurationChanged(c);
        applyLayout();
    }

    private boolean centerHooked = false;

    /** 접은 화면: 미리보기를 위 막대와 아래 버튼 사이 가운데에 둠. 그 사이에 다 안 들어가면 화면 전체 가운데 */
    private FrameLayout.LayoutParams centeredFrameParams() {
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
        int W = root.getWidth(), H = root.getHeight(), bh = bottom.getHeight();
        if (W <= 0 || H <= 0) return fl;
        int fh = Math.round(W * frame.getRatio());
        int top = px(52);
        if (fh <= H - top - bh) {
            fl.topMargin = top;
            fl.bottomMargin = bh;
        }
        return fl;
    }

    private void centerFrame() {
        if (wide || root == null || frame == null) return;
        FrameLayout.LayoutParams n = centeredFrameParams();
        ViewGroup.LayoutParams o = frame.getLayoutParams();
        if (o instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams c = (FrameLayout.LayoutParams) o;
            if (c.gravity == n.gravity && c.topMargin == n.topMargin && c.bottomMargin == n.bottomMargin
                    && c.rightMargin == n.rightMargin) return;
        }
        root.updateViewLayout(frame, n);
    }

    /** 화면이 넓으면(폴드 펼침) 미리보기를 세로 꽉 채우고 버튼은 오른쪽 세로줄로 */
    private void applyLayout() {
        Configuration c = getResources().getConfiguration();
        wide = c.screenWidthDp > c.screenHeightDp * 0.72f;
        int side = px(124);
        FrameLayout.LayoutParams fl, tl, bl;
        if (wide) {
            fl = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
            fl.rightMargin = side;
            tl = new FrameLayout.LayoutParams(-1, px(52), Gravity.TOP);
            tl.rightMargin = side;
            topBar.setBackgroundColor(0x55000000);
            bl = new FrameLayout.LayoutParams(side, -1, Gravity.RIGHT);
            bottom.setOrientation(LinearLayout.VERTICAL);
            bottom.setGravity(Gravity.CENTER);
            bottom.setPadding(0, px(8), 0, px(8));
            bottom.setBackgroundColor(0xFF000000);
            zoomRow.setOrientation(LinearLayout.VERTICAL);
            modeRow.setOrientation(LinearLayout.VERTICAL);
            controls.setOrientation(LinearLayout.VERTICAL);
            bottom.updateViewLayout(zoomRow, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2);
            ml.topMargin = ml.bottomMargin = px(8);
            bottom.updateViewLayout(modeRow, ml);
            bottom.updateViewLayout(controls, new LinearLayout.LayoutParams(-1, px(300)));
            for (View sp : spacers) controls.updateViewLayout(sp, new LinearLayout.LayoutParams(1, 0, 1));
        } else {
            fl = centeredFrameParams();
            tl = new FrameLayout.LayoutParams(-1, px(52), Gravity.TOP);
            topBar.setBackgroundColor(0);
            bl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            bottom.setOrientation(LinearLayout.VERTICAL);
            bottom.setGravity(Gravity.NO_GRAVITY);
            bottom.setPadding(0, px(6), 0, px(18));
            bottom.setBackgroundColor(0x55000000);
            zoomRow.setOrientation(LinearLayout.HORIZONTAL);
            modeRow.setOrientation(LinearLayout.HORIZONTAL);
            controls.setOrientation(LinearLayout.HORIZONTAL);
            bottom.updateViewLayout(zoomRow, new LinearLayout.LayoutParams(-1, px(48)));
            bottom.updateViewLayout(modeRow, new LinearLayout.LayoutParams(-1, px(44)));
            bottom.updateViewLayout(controls, new LinearLayout.LayoutParams(-1, px(90)));
            for (View sp : spacers) controls.updateViewLayout(sp, new LinearLayout.LayoutParams(0, 1, 1));
        }
        FrameLayout.LayoutParams al = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        al.leftMargin = px(10);
        al.topMargin = wide ? px(62) : px(10);
        frame.updateViewLayout(autoBtn, al);
        root.updateViewLayout(frame, fl);
        root.updateViewLayout(topBar, tl);
        root.updateViewLayout(bottom, bl);
        if (!centerHooked) {
            centerHooked = true;
            bottom.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (b - t != ob - ot) root.post(this::centerFrame);
            });
        }
        root.post(this::centerFrame);
        if (camera != null) buildZoomButtons();
        hideZoomPanel.run();
        root.post(this::placeShutter);
    }

    // ───────────── 미세 줌 막대 ─────────────

    private void buildZoomPanel() {
        zoomPanel = new LinearLayout(this);
        zoomPanel.setGravity(Gravity.CENTER_VERTICAL);
        zoomPanel.setPadding(px(6), px(2), px(6), px(2));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xB3000000);
        bg.setCornerRadius(px(24));
        zoomPanel.setBackground(bg);
        TextView minus = chip("−");
        minus.setTextSize(22);
        TextView plus = chip("+");
        plus.setTextSize(22);
        zoomBar = new SeekBar(this);
        zoomBar.setMax(1000);
        zoomLabel = chip("1.0x");
        zoomLabel.setTextSize(14);
        zoomLabel.setMinWidth(px(56));
        zoomPanel.addView(minus, new LinearLayout.LayoutParams(px(44), px(44)));
        zoomPanel.addView(zoomBar, new LinearLayout.LayoutParams(0, px(44), 1));
        zoomPanel.addView(plus, new LinearLayout.LayoutParams(px(44), px(44)));
        zoomPanel.addView(zoomLabel, new LinearLayout.LayoutParams(-2, px(44)));
        zoomPanel.setVisibility(View.GONE);
        root.addView(zoomPanel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        minus.setOnClickListener(v -> stepZoom(-1));
        plus.setOnClickListener(v -> stepZoom(1));
        zoomBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar b, int p, boolean user) {
                if (!user) return;
                float r = barToRatio(p);
                if (camera != null) camera.getCameraControl().setZoomRatio(r);
                zoomLabel.setText(String.format(Locale.US, "%.2fx", r));
                keepZoomPanel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar b) { zoomDragging = true; keepZoomPanel(); }

            @Override
            public void onStopTrackingTouch(SeekBar b) { zoomDragging = false; keepZoomPanel(); }
        });
    }

    private float[] zoomRange() {
        ZoomState z = camera != null ? camera.getCameraInfo().getZoomState().getValue() : null;
        float min = z != null ? z.getMinZoomRatio() : 1f;
        float max = z != null ? Math.min(z.getMaxZoomRatio(), 30f) : 1f;
        if (max <= min) max = min + 0.01f;
        return new float[]{min, max};
    }

    /** 막대 위치 → 배율 (배율이 낮은 쪽은 촘촘하게) */
    private float barToRatio(int p) {
        float[] r = zoomRange();
        return (float) (r[0] * Math.pow(r[1] / r[0], p / 1000.0));
    }

    private int ratioToBar(float ratio) {
        float[] r = zoomRange();
        double v = Math.log(ratio / r[0]) / Math.log(r[1] / r[0]) * 1000;
        return (int) Math.max(0, Math.min(1000, Math.round(v)));
    }

    /** −/+ 버튼: 0.1배씩 (10배 넘으면 0.5배씩) */
    private void stepZoom(int dir) {
        if (camera == null) return;
        ZoomState z = camera.getCameraInfo().getZoomState().getValue();
        if (z == null) return;
        float cur = z.getZoomRatio();
        float step = cur >= 10f ? 0.5f : 0.1f;
        float next = Math.round((cur + dir * step) / step) * step;
        float[] r = zoomRange();
        next = Math.max(r[0], Math.min(r[1], next));
        camera.getCameraControl().setZoomRatio(next);
        zoomLabel.setText(String.format(Locale.US, "%.2fx", next));
        zoomBar.setProgress(ratioToBar(next));
        keepZoomPanel();
    }

    private void syncZoomBar(float cur) {
        if (zoomPanel == null || zoomPanel.getVisibility() != View.VISIBLE || zoomDragging) return;
        zoomBar.setProgress(ratioToBar(cur));
        zoomLabel.setText(String.format(Locale.US, "%.2fx", cur));
    }

    private void showZoomPanel() {
        if (camera == null) return;
        FrameLayout.LayoutParams lp;
        if (wide) {
            int w = Math.min(frame.getWidth() - px(32), px(520));
            lp = new FrameLayout.LayoutParams(w, -2, Gravity.BOTTOM | Gravity.LEFT);
            lp.leftMargin = frame.getLeft() + (frame.getWidth() - w) / 2;
            lp.bottomMargin = px(24);
        } else {
            lp = new FrameLayout.LayoutParams(Math.min(root.getWidth() - px(24), px(480)), -2,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lp.bottomMargin = bottom.getHeight() + px(8);
        }
        root.updateViewLayout(zoomPanel, lp);
        zoomPanel.setVisibility(View.VISIBLE);
        zoomPanel.bringToFront();
        ZoomState z = camera.getCameraInfo().getZoomState().getValue();
        float cur = z != null ? z.getZoomRatio() : 1f;
        zoomBar.setProgress(ratioToBar(cur));
        zoomLabel.setText(String.format(Locale.US, "%.2fx", cur));
        keepZoomPanel();
    }

    private void keepZoomPanel() {
        ui.removeCallbacks(hideZoomPanel);
        ui.postDelayed(hideZoomPanel, 4000);
    }

    private final Runnable hideZoomPanel = () -> {
        if (zoomDragging) { keepZoomPanel(); return; }
        if (zoomPanel != null) zoomPanel.setVisibility(View.GONE);
    };

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
            out.compress(Bitmap.CompressFormat.JPEG, 100, os);
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

    private void openPdf() {
        if (recording != null) return;
        startActivity(new Intent(this, PdfActivity.class));
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
