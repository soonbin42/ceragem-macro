package com.jinanhealing.ceragemmacro;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    public static final String EXTRA_SCROLL_BOTTOM = "scroll_bottom";
    public static volatile String lastReport = null;
    public static volatile String lastDump = null;

    private static final String ACT_ON = "com.jinanhealing.ceragemmacro.ON";
    private static final String ACT_RUN = "com.jinanhealing.ceragemmacro.RUN";
    private static final String ACT_OFF = "com.jinanhealing.ceragemmacro.OFF";

    private static final int RED = Color.parseColor("#C8102E");
    private static final int INK = Color.parseColor("#222222");
    private static final int MUTED = Color.parseColor("#666666");

    private ScrollView scroll;
    private TextView statusView;
    private Button a11yButton;
    private GridLayout grid;
    private EditText countEdit, prefixEdit, modeEdit, playIdEdit;
    private TextView reportView;
    private Button shareDumpButton;
    private Config cfg;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = Config.load(this);
        buildUi();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String a = intent.getAction();
        if (ACT_ON.equals(a)) startMode(MacroService.MODE_ON);
        else if (ACT_RUN.equals(a)) startMode(MacroService.MODE_RUN);
        else if (ACT_OFF.equals(a)) startMode(MacroService.MODE_OFF);
        // 같은 바로가기 인텐트가 화면 회전 등으로 다시 실행되지 않게 지운다
        intent.setAction(Intent.ACTION_MAIN);
        refresh();
        if (intent.getBooleanExtra(EXTRA_SCROLL_BOTTOM, false) && scroll != null) {
            intent.removeExtra(EXTRA_SCROLL_BOTTOM);
            scroll.post(() -> scroll.smoothScrollTo(0, reportView.getTop()));
        }
    }

    private void startMode(int mode) {
        if (countEdit != null) saveFromUi(false);
        MacroService svc = MacroService.instance;
        if (svc == null) {
            Toast.makeText(this, "먼저 접근성에서 '세라젬 일괄조작'을 켜 주세요", Toast.LENGTH_LONG).show();
            openA11ySettings();
            return;
        }
        if (svc.isRunning()) {
            Toast.makeText(this, "이미 실행 중이에요 (볼륨 아래 버튼 = 중지)", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Config.load(this).selectedList().isEmpty()) {
            Toast.makeText(this, "대상 기기를 하나 이상 선택해 주세요", Toast.LENGTH_SHORT).show();
            return;
        }
        svc.start(mode);
    }

    private void openA11ySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- UI

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#FAF7F5"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(32));
        scroll.addView(root);

        TextView title = text("세라젬 일괄조작", 26, INK, true);
        root.addView(title);
        TextView sub = text("선택한 기기를 세라젬 IoT 앱에서 차례로 대신 눌러 줍니다. 실행 중에는 폰을 만지지 마세요. 볼륨 아래 버튼을 누르면 멈춥니다.", 14, MUTED, false);
        sub.setPadding(0, dp(4), 0, dp(14));
        root.addView(sub);

        // 접근성 상태
        LinearLayout statusRow = card(root);
        statusView = text("", 15, INK, true);
        statusRow.addView(statusView);
        a11yButton = smallButton("접근성 설정 열기");
        a11yButton.setOnClickListener(v -> openA11ySettings());
        statusRow.addView(a11yButton);

        // 큰 버튼 3개
        root.addView(bigButton("전체 켜기", RED, v -> startMode(MacroService.MODE_ON)));
        root.addView(bigButton("전체 실행 ▶", Color.parseColor("#1F6F50"), v -> startMode(MacroService.MODE_RUN)));
        root.addView(bigButton("전체 끄기", Color.parseColor("#444444"), v -> startMode(MacroService.MODE_OFF)));

        // 결과
        root.addView(section("최근 결과"));
        LinearLayout reportCard = card(root);
        reportView = text("", 14, INK, false);
        reportView.setTextIsSelectable(true);
        reportCard.addView(reportView);

        // 대상 기기
        root.addView(section("대상 기기"));
        LinearLayout devCard = card(root);
        grid = new GridLayout(this);
        grid.setColumnCount(5);
        devCard.addView(grid);
        LinearLayout selRow = new LinearLayout(this);
        selRow.setOrientation(LinearLayout.HORIZONTAL);
        Button all = smallButton("전체 선택");
        all.setOnClickListener(v -> setAll(true));
        Button none = smallButton("전체 해제");
        none.setOnClickListener(v -> setAll(false));
        selRow.addView(all);
        selRow.addView(none);
        devCard.addView(selRow);

        // 설정
        root.addView(section("설정"));
        LinearLayout setCard = card(root);
        countEdit = field(setCard, "기기 수", String.valueOf(cfg.count), true);
        prefixEdit = field(setCard, "기기 이름 앞부분 (뒤에 01, 02… 자동)", cfg.prefix, false);
        modeEdit = field(setCard, "실행할 모드 이름 (비우면 화면에 뜬 모드 그대로)", cfg.modeName, false);
        playIdEdit = field(setCard, "▶ 버튼 ID (비워 두면 자동으로 찾음)", cfg.playId, false);
        Button save = smallButton("설정 저장");
        save.setOnClickListener(v -> saveFromUi(true));
        setCard.addView(save);

        // 화면 분석
        root.addView(section("화면 분석 (문제 생겼을 때)"));
        LinearLayout dumpCard = card(root);
        dumpCard.addView(text("버튼을 누르고 10초 안에 세라젬 앱의 확인하고 싶은 화면(목록 또는 기기 화면)을 띄워 두세요. 화면 구조가 Download 폴더에 저장되고, 아래 공유 버튼으로 보낼 수 있어요.", 13, MUTED, false));
        Button dump = smallButton("10초 뒤 화면 분석");
        dump.setOnClickListener(v -> {
            MacroService svc = MacroService.instance;
            if (svc == null) {
                Toast.makeText(this, "먼저 접근성 권한을 켜 주세요", Toast.LENGTH_LONG).show();
                openA11ySettings();
                return;
            }
            svc.dumpLater(10);
        });
        dumpCard.addView(dump);
        shareDumpButton = smallButton("분석 결과 공유");
        shareDumpButton.setOnClickListener(v -> shareDump());
        dumpCard.addView(shareDumpButton);

        setContentView(scroll);
        rebuildGrid();
    }

    private void refresh() {
        if (statusView == null) return;
        boolean on = MacroService.instance != null;
        statusView.setText(on ? "● 접근성 권한 켜짐 — 사용 준비 완료" : "● 접근성 권한이 꺼져 있어요");
        statusView.setTextColor(on ? Color.parseColor("#1F6F50") : RED);
        a11yButton.setVisibility(on ? View.GONE : View.VISIBLE);
        String r = lastReport;
        reportView.setText(r == null ? "아직 실행 기록이 없어요." : r);
        shareDumpButton.setVisibility(lastDump == null ? View.GONE : View.VISIBLE);
    }

    private void rebuildGrid() {
        grid.removeAllViews();
        for (int i = 1; i <= cfg.count; i++) {
            final int n = i;
            CheckBox cb = new CheckBox(this);
            cb.setText(String.format(java.util.Locale.US, "%02d", i));
            cb.setTextSize(16);
            cb.setChecked(cfg.selected.contains(i));
            cb.setOnCheckedChangeListener((b, checked) -> {
                if (checked) cfg.selected.add(n);
                else cfg.selected.remove(n);
                cfg.save(this);
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            cb.setLayoutParams(lp);
            grid.addView(cb);
        }
    }

    private void setAll(boolean on) {
        cfg.selected.clear();
        if (on) for (int i = 1; i <= cfg.count; i++) cfg.selected.add(i);
        cfg.save(this);
        rebuildGrid();
    }

    private void saveFromUi(boolean notify) {
        int count = cfg.count;
        try {
            count = Integer.parseInt(countEdit.getText().toString().trim());
        } catch (NumberFormatException ignored) {
        }
        count = Math.max(1, Math.min(30, count));
        if (count > cfg.count) for (int i = cfg.count + 1; i <= count; i++) cfg.selected.add(i);
        cfg.count = count;
        String p = prefixEdit.getText().toString().trim();
        if (!p.isEmpty()) cfg.prefix = p;
        cfg.modeName = modeEdit.getText().toString().trim();
        cfg.playId = playIdEdit.getText().toString().trim();
        cfg.save(this);
        rebuildGrid();
        countEdit.setText(String.valueOf(cfg.count));
        if (notify) Toast.makeText(this, "저장했어요", Toast.LENGTH_SHORT).show();
    }

    private void shareDump() {
        String d = lastDump;
        if (d == null) return;
        if (d.length() > 150000) d = d.substring(0, 150000) + "\n…(생략)";
        Intent s = new Intent(Intent.ACTION_SEND);
        s.setType("text/plain");
        s.putExtra(Intent.EXTRA_SUBJECT, "세라젬 화면 분석");
        s.putExtra(Intent.EXTRA_TEXT, d);
        startActivity(Intent.createChooser(s, "화면 분석 결과 보내기"));
    }

    // ---------------------------------------------------------------- 작은 위젯 도우미

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    private TextView section(String s) {
        TextView t = text(s, 15, MUTED, true);
        t.setPadding(dp(4), dp(18), 0, dp(6));
        return t;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.parseColor("#EAE3DF"));
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        c.setLayoutParams(lp);
        parent.addView(c);
        return c;
    }

    private Button bigButton(String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(22);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(16));
        b.setBackground(bg);
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(76));
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private Button smallButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private EditText field(LinearLayout parent, String label, String value, boolean number) {
        TextView l = text(label, 13, MUTED, false);
        l.setPadding(0, dp(8), 0, 0);
        parent.addView(l);
        EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextSize(16);
        e.setInputType(number ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
        parent.addView(e);
        return e;
    }
}
