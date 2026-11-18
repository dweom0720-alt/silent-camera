package com.silentcam.app;

import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 이 앱으로 찍은 사진을 골라 압축 PDF로 만드는 화면 */
public class PdfActivity extends AppCompatActivity {

    private static final int F_ALL = 0, F_PHOTO = 1, F_RANGE = 2;

    private float dp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService thumbs = Executors.newFixedThreadPool(3);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LruCache<Long, Bitmap> cache = new LruCache<Long, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(Long k, Bitmap b) { return b.getByteCount(); }
    };

    private static final class Item { long id; Uri uri; boolean range; }

    private final List<Item> all = new ArrayList<>();
    private final List<Item> shown = new ArrayList<>();
    private final List<Long> picked = new ArrayList<>(); // 고른 순서 = PDF 쪽 순서

    private int filter = F_ALL;
    private int level = PdfBuild.NORMAL;
    private TextView[] filterChips, levelChips;
    private TextView makeBtn, selectAllBtn, levelDesc, emptyText;
    private CheckBox cleanBox;
    private GridView grid;
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        level = getSharedPreferences("cam", MODE_PRIVATE).getInt("pdfLevel", PdfBuild.NORMAL);
        setContentView(build());
        load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        thumbs.shutdownNow();
        worker.shutdown();
    }

    private int px(float v) { return Math.round(v * dp); }

    private TextView chip(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(12), px(8), px(12), px(8));
        return t;
    }

    private void styleChip(TextView t, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(18));
        g.setColor(on ? 0xFFFFD54F : 0xFF2A2A2E);
        t.setBackground(g);
        t.setTextColor(on ? Color.BLACK : Color.WHITE);
        t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121214);

        // 머리줄
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(px(4), px(4), px(8), px(4));
        TextView back = chip("←");
        back.setTextSize(22);
        back.setOnClickListener(v -> finish());
        TextView title = chip("PDF 만들기");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        selectAllBtn = chip("전체 선택");
        selectAllBtn.setOnClickListener(v -> toggleAll());
        head.addView(back, new LinearLayout.LayoutParams(px(48), px(48)));
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(selectAllBtn);
        title.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        root.addView(head);

        // 사진 종류 고르기
        LinearLayout fr = new LinearLayout(this);
        fr.setPadding(px(10), 0, px(10), px(6));
        String[] fn = {"전체", "사진", "범위촬영"};
        filterChips = new TextView[fn.length];
        for (int i = 0; i < fn.length; i++) {
            TextView t = chip(fn[i]);
            final int f = i;
            t.setOnClickListener(v -> { filter = f; applyFilter(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = px(6);
            fr.addView(t, lp);
            filterChips[i] = t;
        }
        root.addView(fr);

        // 사진 목록
        FrameLayout gridBox = new FrameLayout(this);
        grid = new GridView(this);
        grid.setColumnWidth(px(108));
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(px(3));
        grid.setVerticalSpacing(px(3));
        grid.setPadding(px(3), 0, px(3), 0);
        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> togglePick(shown.get(pos).id));
        gridBox.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        emptyText = chip("이 앱으로 찍은 사진이 없습니다");
        emptyText.setTextColor(0xFF9A9AA0);
        emptyText.setVisibility(View.GONE);
        gridBox.addView(emptyText, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(gridBox, new LinearLayout.LayoutParams(-1, 0, 1));

        // 아래쪽: 압축 정도 + 만들기
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(0xFF1C1C20);
        bottom.setPadding(px(12), px(10), px(12), px(14));
        TextView lt = chip("압축 정도");
        lt.setGravity(Gravity.LEFT);
        lt.setPadding(px(2), 0, 0, px(6));
        lt.setTextColor(0xFFB0B0B8);
        lt.setTextSize(13);
        bottom.addView(lt);
        LinearLayout lr = new LinearLayout(this);
        levelChips = new TextView[PdfBuild.NAMES.length];
        for (int i = 0; i < PdfBuild.NAMES.length; i++) {
            TextView t = chip(PdfBuild.NAMES[i]);
            t.setTextSize(13);
            final int l = i;
            t.setOnClickListener(v -> setLevel(l));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.rightMargin = px(4);
            lr.addView(t, lp);
            levelChips[i] = t;
        }
        bottom.addView(lr);
        levelDesc = chip("");
        levelDesc.setGravity(Gravity.LEFT);
        levelDesc.setPadding(px(2), px(6), 0, 0);
        levelDesc.setTextColor(0xFFB0B0B8);
        levelDesc.setTextSize(12);
        bottom.addView(levelDesc);
        cleanBox = new CheckBox(this);
        cleanBox.setText("문서 보정 (그림자 지우고 바탕 하얗게)");
        cleanBox.setTextColor(Color.WHITE);
        cleanBox.setChecked(getSharedPreferences("cam", MODE_PRIVATE).getBoolean("pdfClean", false));
        cleanBox.setOnCheckedChangeListener((c, on) ->
                getSharedPreferences("cam", MODE_PRIVATE).edit().putBoolean("pdfClean", on).apply());
        bottom.addView(cleanBox);
        makeBtn = chip("");
        makeBtn.setTextSize(16);
        makeBtn.setTypeface(Typeface.DEFAULT_BOLD);
        makeBtn.setOnClickListener(v -> make());
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, px(52));
        ml.topMargin = px(6);
        bottom.addView(makeBtn, ml);
        root.addView(bottom);

        setLevel(level);
        applyFilter();
        return root;
    }

    private void setLevel(int l) {
        level = l;
        getSharedPreferences("cam", MODE_PRIVATE).edit().putInt("pdfLevel", l).apply();
        for (int i = 0; i < levelChips.length; i++) styleChip(levelChips[i], i == l);
        levelDesc.setText(PdfBuild.DESC[l]);
        cleanBox.setEnabled(l != PdfBuild.BW);
        cleanBox.setAlpha(l == PdfBuild.BW ? 0.4f : 1f);
    }

    // ───────── 사진 불러오기 (이 앱이 저장한 사진만 보임) ─────────

    private void load() {
        worker.execute(() -> {
            List<Item> list = new ArrayList<>();
            String[] cols = {MediaStore.Images.Media._ID, MediaStore.Images.Media.RELATIVE_PATH};
            String sel = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ? OR "
                    + MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
            String[] args = {"DCIM/Camera%", "DCIM/범위촬영%"};
            try (Cursor c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, sel, args,
                    MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                if (c != null) {
                    while (c.moveToNext()) {
                        Item it = new Item();
                        it.id = c.getLong(0);
                        String path = c.getString(1);
                        it.range = path != null && path.contains("범위촬영");
                        it.uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it.id);
                        list.add(it);
                    }
                }
            } catch (Exception ignored) { }
            ui.post(() -> {
                all.clear();
                all.addAll(list);
                applyFilter();
            });
        });
    }

    private void applyFilter() {
        for (int i = 0; i < filterChips.length; i++) styleChip(filterChips[i], i == filter);
        shown.clear();
        for (Item it : all) {
            if (filter == F_PHOTO && it.range) continue;
            if (filter == F_RANGE && !it.range) continue;
            shown.add(it);
        }
        adapter.notifyDataSetChanged();
        emptyText.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        refreshButtons();
    }

    private void togglePick(long id) {
        if (picked.contains(id)) picked.remove(id);
        else picked.add(id);
        adapter.notifyDataSetChanged();
        refreshButtons();
    }

    private void toggleAll() {
        boolean allOn = !shown.isEmpty();
        for (Item it : shown) if (!picked.contains(it.id)) { allOn = false; break; }
        if (allOn) { for (Item it : shown) picked.remove(it.id); }
        else {
            // 찍은 순서(오래된 것부터)대로 쪽 번호
            for (int i = shown.size() - 1; i >= 0; i--) if (!picked.contains(shown.get(i).id)) picked.add(shown.get(i).id);
        }
        adapter.notifyDataSetChanged();
        refreshButtons();
    }

    private void refreshButtons() {
        int n = picked.size();
        makeBtn.setText(n == 0 ? "사진을 눌러 고르세요 (고른 순서대로 쪽이 됨)" : "PDF 만들기 (" + n + "장)");
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(14));
        g.setColor(n == 0 ? 0xFF3A3A40 : 0xFF2F6BFF);
        makeBtn.setBackground(g);
        makeBtn.setTextColor(n == 0 ? 0xFF9A9AA0 : Color.WHITE);
        if (n > 0) makeBtn.setTextSize(16); else makeBtn.setTextSize(13);
        boolean allOn = !shown.isEmpty();
        for (Item it : shown) if (!picked.contains(it.id)) { allOn = false; break; }
        selectAllBtn.setText(allOn ? "선택 해제" : "전체 선택");
    }

    // ───────── PDF 만들기 ─────────

    private void make() {
        if (picked.isEmpty()) return;
        List<Uri> uris = new ArrayList<>();
        for (long id : picked) uris.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id));
        final int lv = level;
        final boolean clean = cleanBox.isChecked() && lv != PdfBuild.BW;
        String name = "무음카메라_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".pdf";

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(24), px(20), px(24), px(8));
        TextView msg = new TextView(this);
        msg.setText("PDF 만드는 중… 0/" + uris.size());
        msg.setTextSize(15);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(uris.size());
        box.addView(msg);
        box.addView(bar);
        AlertDialog dlg = new AlertDialog.Builder(this).setView(box).setCancelable(false).show();

        worker.execute(() -> {
            try {
                PdfBuild.Result r = PdfBuild.make(this, uris, lv, clean, name, (d, t) -> ui.post(() -> {
                    msg.setText("PDF 만드는 중… " + d + "/" + t);
                    bar.setProgress(d);
                }));
                ui.post(() -> { dlg.dismiss(); showDone(r); });
            } catch (Exception e) {
                ui.post(() -> {
                    dlg.dismiss();
                    new AlertDialog.Builder(this).setTitle("만들지 못했습니다")
                            .setMessage(String.valueOf(e.getMessage())).setPositiveButton("확인", null).show();
                });
            }
        });
    }

    private static String size(long b) {
        if (b < 1024 * 1024) return String.format(Locale.US, "%.0fKB", b / 1024.0);
        return String.format(Locale.US, "%.1fMB", b / 1024.0 / 1024.0);
    }

    private void showDone(PdfBuild.Result r) {
        new AlertDialog.Builder(this)
                .setTitle("PDF 저장 완료")
                .setMessage(r.name + "\n\n" + r.pages + "쪽 · " + size(r.bytes) + "\n위치: 다운로드 > 무음카메라")
                .setPositiveButton("공유", (d, w) -> {
                    Intent s = new Intent(Intent.ACTION_SEND);
                    s.setType("application/pdf");
                    s.putExtra(Intent.EXTRA_STREAM, r.uri);
                    s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(s, "PDF 보내기"));
                })
                .setNeutralButton("열기", (d, w) -> {
                    try {
                        Intent o = new Intent(Intent.ACTION_VIEW);
                        o.setDataAndType(r.uri, "application/pdf");
                        o.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(o);
                    } catch (Exception e) {
                        new AlertDialog.Builder(this).setMessage("PDF를 열 수 있는 앱이 없습니다")
                                .setPositiveButton("확인", null).show();
                    }
                })
                .setNegativeButton("닫기", null)
                .show();
        picked.clear();
        adapter.notifyDataSetChanged();
        refreshButtons();
    }

    // ───────── 사진 칸 ─────────

    private static class Square extends FrameLayout {
        Square(Context c) { super(c); }

        @Override
        protected void onMeasure(int w, int h) { super.onMeasure(w, w); }
    }

    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int p) { return shown.get(p); }
        @Override public long getItemId(int p) { return shown.get(p).id; }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            Square cell;
            ImageView img;
            TextView badge, tag;
            View shade;
            if (cv == null) {
                cell = new Square(PdfActivity.this);
                img = new ImageView(PdfActivity.this);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setBackgroundColor(0xFF2A2A2E);
                cell.addView(img, new FrameLayout.LayoutParams(-1, -1));
                shade = new View(PdfActivity.this);
                cell.addView(shade, new FrameLayout.LayoutParams(-1, -1));
                tag = new TextView(PdfActivity.this);
                tag.setTextColor(Color.WHITE);
                tag.setTextSize(10);
                tag.setPadding(px(4), px(1), px(4), px(1));
                tag.setBackgroundColor(0x99000000);
                FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT);
                cell.addView(tag, tl);
                badge = new TextView(PdfActivity.this);
                badge.setGravity(Gravity.CENTER);
                badge.setTextSize(13);
                badge.setTypeface(Typeface.DEFAULT_BOLD);
                FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(px(28), px(28), Gravity.TOP | Gravity.RIGHT);
                bl.topMargin = bl.rightMargin = px(6);
                cell.addView(badge, bl);
                cell.setTag(new Object[]{img, badge, shade, tag});
            } else {
                cell = (Square) cv;
                Object[] o = (Object[]) cell.getTag();
                img = (ImageView) o[0];
                badge = (TextView) o[1];
                shade = (View) o[2];
                tag = (TextView) o[3];
            }
            Item it = shown.get(pos);
            int order = picked.indexOf(it.id);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            if (order >= 0) {
                bg.setColor(0xFF2F6BFF);
                bg.setStroke(px(2), Color.WHITE);
                badge.setText(String.valueOf(order + 1));
                badge.setTextColor(Color.WHITE);
                shade.setBackgroundColor(0x552F6BFF);
            } else {
                bg.setColor(0x44000000);
                bg.setStroke(px(2), Color.WHITE);
                badge.setText("");
                shade.setBackgroundColor(0);
            }
            badge.setBackground(bg);
            tag.setText(it.range ? "범위" : "사진");

            final ImageView iv = img;
            img.setTag(it.id);
            Bitmap b = cache.get(it.id);
            if (b != null) img.setImageBitmap(b);
            else {
                img.setImageDrawable(null);
                final long id = it.id;
                final Uri u = it.uri;
                thumbs.execute(() -> {
                    try {
                        Bitmap t = getContentResolver().loadThumbnail(u, new Size(px(160), px(160)), null);
                        cache.put(id, t);
                        ui.post(() -> { if (Long.valueOf(id).equals(iv.getTag())) iv.setImageBitmap(t); });
                    } catch (Exception ignored) { }
                });
            }
            return cell;
        }
    }
}
