package com.silentcam.app;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** PDF 여러 개를 한 번에 골라 용량을 줄이는 화면. '내 파일'에서 공유로 보내도 열림 */
public class PdfShrinkActivity extends AppCompatActivity {

    private static final int PICK = 7;

    private float dp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private static final class Row {
        Uri uri; String name; long size;
        TextView status;
        Uri out;
        boolean done;
    }

    private final List<Row> rows = new ArrayList<>();
    private LinearLayout list;
    private TextView emptyText, startBtn, shareBtn, levelDesc, summary;
    private TextView[] levelChips;
    private int level = PdfBuild.NORMAL;
    private boolean running = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        level = getSharedPreferences("cam", MODE_PRIVATE).getInt("shrinkLevel", PdfBuild.NORMAL);
        setContentView(build());
        takeIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        takeIntent(i);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdown();
    }

    private int px(float v) { return Math.round(v * dp); }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private void round(View v, int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(px(r));
        v.setBackground(g);
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121214);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(px(4), px(4), px(8), px(4));
        TextView back = text("←", 22, Color.WHITE);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        TextView title = text("PDF 용량 줄이기", 18, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(back, new LinearLayout.LayoutParams(px(48), px(48)));
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(head);

        TextView pick = text("＋ PDF 파일 고르기 (여러 개 가능)", 15, Color.WHITE);
        pick.setGravity(Gravity.CENTER);
        round(pick, 0xFF2A2A2E, 14);
        pick.setOnClickListener(v -> pickFiles());
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, px(50));
        pl.setMargins(px(12), px(4), px(12), px(8));
        root.addView(pick, pl);

        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(px(12), 0, px(12), px(8));
        box.addView(list);
        emptyText = text("줄일 PDF를 고르세요.\n'내 파일'에서 PDF를 여러 개 선택 → 공유 → 무음카메라 로 보내도 됩니다.",
                14, 0xFF9A9AA0);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(px(24), px(40), px(24), px(40));
        box.addView(emptyText);
        sv.addView(box);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(0xFF1C1C20);
        bottom.setPadding(px(12), px(10), px(12), px(14));
        bottom.addView(text("압축 정도", 13, 0xFFB0B0B8));
        LinearLayout lr = new LinearLayout(this);
        lr.setPadding(0, px(6), 0, 0);
        levelChips = new TextView[PdfBuild.NAMES.length];
        for (int i = 0; i < levelChips.length; i++) {
            TextView t = text(PdfBuild.NAMES[i], 13, Color.WHITE);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, px(8), 0, px(8));
            final int l = i;
            t.setOnClickListener(v -> { if (!running) setLevel(l); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.rightMargin = px(4);
            lr.addView(t, lp);
            levelChips[i] = t;
        }
        bottom.addView(lr);
        levelDesc = text("", 12, 0xFFB0B0B8);
        levelDesc.setPadding(px(2), px(6), 0, 0);
        bottom.addView(levelDesc);
        TextView note = text("※ 각 쪽을 그림으로 바꿔 압축함 → 줄인 PDF에서는 글자 검색·복사가 안 됨. 원본은 그대로 남음",
                11, 0xFF8A8A92);
        note.setPadding(px(2), px(4), 0, 0);
        bottom.addView(note);
        summary = text("", 13, 0xFFFFD54F);
        summary.setPadding(px(2), px(6), 0, 0);
        summary.setVisibility(View.GONE);
        bottom.addView(summary);

        LinearLayout btns = new LinearLayout(this);
        startBtn = text("", 16, Color.WHITE);
        startBtn.setGravity(Gravity.CENTER);
        startBtn.setTypeface(Typeface.DEFAULT_BOLD);
        startBtn.setOnClickListener(v -> start());
        shareBtn = text("모두 공유", 15, Color.WHITE);
        shareBtn.setGravity(Gravity.CENTER);
        round(shareBtn, 0xFF3A3A40, 14);
        shareBtn.setVisibility(View.GONE);
        shareBtn.setOnClickListener(v -> shareAll());
        btns.addView(startBtn, new LinearLayout.LayoutParams(0, px(52), 2));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, px(52), 1);
        sl.leftMargin = px(8);
        btns.addView(shareBtn, sl);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = px(8);
        bottom.addView(btns, bl);
        root.addView(bottom);

        setLevel(level);
        refresh();
        return root;
    }

    private void setLevel(int l) {
        level = l;
        getSharedPreferences("cam", MODE_PRIVATE).edit().putInt("shrinkLevel", l).apply();
        for (int i = 0; i < levelChips.length; i++) {
            round(levelChips[i], i == l ? 0xFFFFD54F : 0xFF2A2A2E, 18);
            levelChips[i].setTextColor(i == l ? Color.BLACK : Color.WHITE);
            levelChips[i].setTypeface(i == l ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        levelDesc.setText(PdfBuild.DESC[l]);
    }

    // ───────── 파일 받기 ─────────

    private void pickFiles() {
        if (running) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || res != RESULT_OK || data == null) return;
        List<Uri> got = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int k = 0; k < data.getClipData().getItemCount(); k++) got.add(data.getClipData().getItemAt(k).getUri());
        } else if (data.getData() != null) got.add(data.getData());
        addAll(got);
    }

    @SuppressWarnings("deprecation")
    private void takeIntent(Intent i) {
        if (i == null || running) return;
        List<Uri> got = new ArrayList<>();
        String a = i.getAction();
        if (Intent.ACTION_SEND.equals(a)) {
            Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) got.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(a)) {
            ArrayList<Uri> us = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (us != null) got.addAll(us);
        } else if (Intent.ACTION_VIEW.equals(a) && i.getData() != null) {
            got.add(i.getData());
        }
        addAll(got);
    }

    private void addAll(List<Uri> got) {
        if (got.isEmpty()) return;
        boolean hadDone = false;
        for (Row r : rows) if (r.done) { hadDone = true; break; }
        if (hadDone) { rows.clear(); list.removeAllViews(); } // 지난번 결과는 비우고 새로
        for (Uri u : got) {
            boolean dup = false;
            for (Row r : rows) if (r.uri.equals(u)) { dup = true; break; }
            if (dup) continue;
            Row r = new Row();
            r.uri = u;
            r.name = PdfShrink.displayName(getContentResolver(), u);
            r.size = PdfShrink.size(getContentResolver(), u);
            rows.add(r);
            addRowView(r);
        }
        summary.setVisibility(View.GONE);
        refresh();
    }

    private void addRowView(Row r) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(12), px(10), px(6), px(10));
        round(row, 0xFF1E1E22, 12);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(r.name, 14, Color.WHITE);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        r.status = text("원본 " + fmt(r.size) + " · 대기", 12, 0xFFB0B0B8);
        col.addView(name);
        col.addView(r.status);
        TextView x = text("✕", 16, 0xFF9A9AA0);
        x.setGravity(Gravity.CENTER);
        x.setOnClickListener(v -> {
            if (running) return;
            rows.remove(r);
            list.removeView(row);
            refresh();
        });
        r.status.setOnClickListener(v -> openOut(r));
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(x, new LinearLayout.LayoutParams(px(40), px(40)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = px(6);
        list.addView(row, lp);
    }

    private void refresh() {
        int waiting = 0, ok = 0;
        for (Row r : rows) { if (!r.done) waiting++; if (r.out != null) ok++; }
        emptyText.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        if (running) startBtn.setText("줄이는 중…");
        else if (waiting == 0) startBtn.setText(rows.isEmpty() ? "PDF를 먼저 고르세요" : "완료");
        else startBtn.setText("줄이기 시작 (" + waiting + "개)");
        boolean can = !running && waiting > 0;
        round(startBtn, can ? 0xFF2F6BFF : 0xFF3A3A40, 14);
        startBtn.setTextColor(can ? Color.WHITE : 0xFF9A9AA0);
        shareBtn.setVisibility(!running && ok > 0 ? View.VISIBLE : View.GONE);
    }

    // ───────── 줄이기 ─────────

    private void start() {
        if (running) return;
        List<Row> todo = new ArrayList<>();
        for (Row r : rows) if (!r.done) todo.add(r);
        if (todo.isEmpty()) return;
        running = true;
        refresh();
        final int lv = level;
        worker.execute(() -> {
            long sumBefore = 0, sumAfter = 0;
            for (Row r : todo) {
                ui.post(() -> r.status.setText("원본 " + fmt(r.size) + " · 준비 중…"));
                try {
                    PdfShrink.Result res = PdfShrink.shrink(this, r.uri, lv, (d, t) ->
                            ui.post(() -> r.status.setText("원본 " + fmt(r.size) + " · " + d + "/" + t + "쪽 처리 중")));
                    r.done = true;
                    if (res.notSmaller) {
                        sumBefore += res.before;
                        sumAfter += res.before;
                        ui.post(() -> {
                            r.status.setText(fmt(res.before) + " · 이미 작아서 그대로 둠 (더 낮은 압축 정도로 다시 해보세요)");
                            r.status.setTextColor(0xFFB0B0B8);
                        });
                    } else {
                        r.out = res.uri;
                        sumBefore += res.before;
                        sumAfter += res.after;
                        int pct = res.before > 0 ? (int) Math.round(100.0 * (res.before - res.after) / res.before) : 0;
                        ui.post(() -> {
                            r.status.setText(fmt(res.before) + " → " + fmt(res.after) + " (" + pct + "% 감소) · 눌러서 열기");
                            r.status.setTextColor(0xFF7CD992);
                        });
                    }
                } catch (Exception e) {
                    r.done = true;
                    String m = e.getMessage() == null ? "알 수 없는 오류" : e.getMessage();
                    ui.post(() -> {
                        r.status.setText("실패: " + m);
                        r.status.setTextColor(0xFFFF7A7A);
                    });
                }
            }
            final long b = sumBefore, a = sumAfter;
            ui.post(() -> {
                running = false;
                summary.setText("합계 " + fmt(b) + " → " + fmt(a) + " · 저장 위치: 다운로드 > 무음카메라 (파일명 끝 '_압축')");
                summary.setVisibility(View.VISIBLE);
                refresh();
            });
        });
    }

    private void openOut(Row r) {
        if (r.out == null) return;
        try {
            Intent o = new Intent(Intent.ACTION_VIEW);
            o.setDataAndType(r.out, "application/pdf");
            o.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(o);
        } catch (Exception ignored) { }
    }

    private void shareAll() {
        ArrayList<Uri> outs = new ArrayList<>();
        for (Row r : rows) if (r.out != null) outs.add(r.out);
        if (outs.isEmpty()) return;
        Intent s;
        if (outs.size() == 1) {
            s = new Intent(Intent.ACTION_SEND);
            s.putExtra(Intent.EXTRA_STREAM, outs.get(0));
        } else {
            s = new Intent(Intent.ACTION_SEND_MULTIPLE);
            s.putParcelableArrayListExtra(Intent.EXTRA_STREAM, outs);
        }
        s.setType("application/pdf");
        s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(s, "줄인 PDF 보내기"));
    }

    private static String fmt(long b) {
        if (b < 0) return "?";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.0fKB", b / 1024.0);
        return String.format(Locale.US, "%.1fMB", b / 1024.0 / 1024.0);
    }
}
