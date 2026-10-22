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
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    public static final String EXTRA_SCROLL_BOTTOM = "scroll_bottom";
    public static volatile String lastReport = null;
    public static volatile String lastDump = null;
    public static volatile String lastIssueReport = null;

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
    private EditText countEdit, prefixEdit, modeEdit, playIdEdit, changeEdit, changeNoEdit, changeSpeedEdit;
    private TextView reportView;
    private Button shareDumpButton, shareIssueButton;
    private TextView histInfo;
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
            Toast.makeText(this, "이미 실행 중이에요 (볼륨 아래 두 번 = 중지)", Toast.LENGTH_SHORT).show();
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
        TextView sub = text("선택한 기기를 세라젬 IoT 앱에서 차례로 대신 눌러 줍니다. 실행 중에는 폰을 만지지 마세요. 볼륨 아래 버튼을 두 번 누르면 멈춥니다.", 14, MUTED, false);
        sub.setPadding(0, dp(4), 0, dp(14));
        root.addView(sub);

        // 정사각형 버튼 2×2
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(squareButton("전체\n켜기", "⚡", RED, v -> startMode(MacroService.MODE_ON)));
        row1.addView(squareButton("전체\n실행", "▶", Color.parseColor("#1F6F50"), v -> startMode(MacroService.MODE_RUN)));
        root.addView(row1);
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(squareButton("전체\n끄기", "■", Color.parseColor("#444444"), v -> startMode(MacroService.MODE_OFF)));
        row2.addView(splitModeButtons());
        root.addView(row2);

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


        // 결과
        root.addView(section("최근 결과"));
        LinearLayout reportCard = card(root);
        reportView = text("", 14, INK, false);
        reportView.setTextIsSelectable(true);
        reportCard.addView(reportView);
        shareIssueButton = smallButton("진행 기록 공유");
        shareIssueButton.setOnClickListener(v -> shareText(lastIssueReport, "세라젬 일괄조작 진행 기록"));
        reportCard.addView(shareIssueButton);

        // 속도
        root.addView(section("속도"));
        LinearLayout speedCard = card(root);
        RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(RadioGroup.HORIZONTAL);
        String[] labels = {"안정", "보통", "빠름"};
        for (int i = 0; i < 3; i++) {
            final int sp = i;
            RadioButton rb = new RadioButton(this);
            rb.setText(labels[i]);
            rb.setTextSize(16);
            rb.setId(View.generateViewId());
            rb.setChecked(cfg.speed == i);
            rb.setOnClickListener(v -> {
                cfg.speed = sp;
                cfg.save(this);
            });
            RadioGroup.LayoutParams rlp = new RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            rb.setLayoutParams(rlp);
            rg.addView(rb);
        }
        speedCard.addView(rg);
        speedCard.addView(text("빠름에서 일부 기기가 안 켜지면 보통이나 안정으로 바꿔 보세요.", 13, MUTED, false));

        // 설정
        root.addView(section("설정"));
        LinearLayout setCard = card(root);
        countEdit = field(setCard, "기기 수", String.valueOf(cfg.count), true);
        prefixEdit = field(setCard, "기기 이름 앞부분 (뒤에 01, 02… 자동)", cfg.prefix, false);
        modeEdit = field(setCard, "실행할 페이지 번호 (1=척추, 2=음파 진동 · 모드 이름으로 적어도 됨)", cfg.modeName, false);
        changeEdit = field(setCard, "모드 변경 버튼으로 바꿀 모드", cfg.changeModeName, false);
        changeNoEdit = field(setCard, "그 모드의 번호 (모드 창에서 몇 번인지, 릴렉스 = 2)", cfg.changeModeNo, true);
        changeSpeedEdit = field(setCard, "모드 변경 후 속도 (저속 / 정속 / 고속 · 비우면 그대로)", cfg.changeSpeed, false);
        playIdEdit = field(setCard, "▶ 버튼 ID (비워 두면 자동으로 찾음)", cfg.playId, false);
        Button save = smallButton("설정 저장");
        save.setOnClickListener(v -> saveFromUi(true));
        setCard.addView(save);

        // 접근성 상태
        LinearLayout statusRow = card(root);
        statusView = text("", 15, INK, true);
        statusRow.addView(statusView);
        a11yButton = smallButton("접근성 설정 열기");
        a11yButton.setOnClickListener(v -> openA11ySettings());
        statusRow.addView(a11yButton);

        // 실패 기록 모음
        root.addView(section("실패 기록 모음"));
        LinearLayout histCard = card(root);
        histInfo = text("", 14, INK, false);
        histCard.addView(histInfo);
        histCard.addView(text("실패·확인 안 됨·앱 재실행·네트워크 알림이 있었던 실행만 자동으로 쌓여요. 공유하면 맨 위에 기기별·원인별 횟수가 정리돼요.", 13, MUTED, false));
        LinearLayout histRow = new LinearLayout(this);
        histRow.setOrientation(LinearLayout.HORIZONTAL);
        Button histShare = smallButton("실패 기록 공유");
        histShare.setOnClickListener(v -> {
            String h = FailHistory.withSummary(this);
            if (h.isEmpty()) Toast.makeText(this, "아직 쌓인 실패 기록이 없어요", Toast.LENGTH_SHORT).show();
            else shareText(h, "세라젬 일괄조작 실패 기록 모음");
        });
        Button histClear = smallButton("기록 지우기");
        histClear.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setMessage("실패 기록을 모두 지울까요?")
                .setPositiveButton("지우기", (d, w) -> {
                    FailHistory.clear(this);
                    refresh();
                })
                .setNegativeButton("취소", null)
                .show());
        histRow.addView(histShare);
        histRow.addView(histClear);
        histCard.addView(histRow);

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
        shareIssueButton.setVisibility(lastIssueReport == null ? View.GONE : View.VISIBLE);
        int hc = FailHistory.count(this);
        histInfo.setText(hc == 0 ? "쌓인 실패 기록 없음" : "쌓인 실패 기록 " + hc + "회");
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
        String cm = changeEdit.getText().toString().trim();
        cfg.changeModeName = cm.isEmpty() ? "릴렉스 모드" : cm;
        cfg.changeModeNo = changeNoEdit.getText().toString().trim();
        cfg.changeSpeed = changeSpeedEdit.getText().toString().trim();
        cfg.save(this);
        rebuildGrid();
        countEdit.setText(String.valueOf(cfg.count));
        if (notify) Toast.makeText(this, "저장했어요", Toast.LENGTH_SHORT).show();
    }

    private void shareDump() {
        shareText(lastDump, "세라젬 화면 분석");
    }

    private void shareText(String d, String subject) {
        if (d == null) return;
        if (d.length() > 150000) d = d.substring(0, 150000) + "\n…(생략)";
        Intent s = new Intent(Intent.ACTION_SEND);
        s.setType("text/plain");
        s.putExtra(Intent.EXTRA_SUBJECT, subject);
        s.putExtra(Intent.EXTRA_TEXT, d);
        startActivity(Intent.createChooser(s, subject + " 보내기"));
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

    /** 가로 폭만큼 세로도 맞추는 정사각형 버튼. */
    private static class SquareButton extends Button {
        SquareButton(android.content.Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec);
        }
    }

    /** 가로 폭만큼 세로를 맞추는 세로 묶음(정사각형 칸을 위아래 반으로 나눌 때). */
    private static class SquareColumn extends LinearLayout {
        SquareColumn(android.content.Context c) {
            super(c);
            setOrientation(VERTICAL);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec);
        }
    }

    /** 모드 변경 칸을 반으로: 위 = 릴렉스 + 고속, 아래 = 릴렉스 + 저속. */
    private View splitModeButtons() {
        SquareColumn col = new SquareColumn(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.setMargins(dp(5), dp(5), dp(5), dp(5));
        col.setLayoutParams(clp);
        col.addView(halfButton("↻ 모드 변경\n고속", Color.parseColor("#6A4C93"),
                v -> startMode(MacroService.MODE_CHANGE_FAST), true));
        col.addView(halfButton("↻ 모드 변경\n저속", Color.parseColor("#4E7CA8"),
                v -> startMode(MacroService.MODE_CHANGE_SLOW), false));
        return col;
    }

    private Button halfButton(String label, int color, View.OnClickListener l, boolean top) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(19);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(20));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        if (top) lp.bottomMargin = dp(5);
        else lp.topMargin = dp(5);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private Button squareButton(String label, String icon, int color, View.OnClickListener l) {
        Button b = new SquareButton(this);
        b.setText(icon + "\n\n" + label);
        b.setTextSize(22);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setLineSpacing(0, 1.05f);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(20));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(5), dp(5), dp(5), dp(5));
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
