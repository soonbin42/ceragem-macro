package com.jinanhealing.ceragemmacro;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 세라젬 IoT 앱 화면을 읽어서 대신 눌러 주는 접근성 서비스.
 * 고정 대기 대신 "원하는 화면 요소가 나타날 때까지" 기다렸다가 동작한다.
 */
public class MacroService extends AccessibilityService {

    public static final int MODE_ON = 1;
    public static final int MODE_RUN = 2;
    public static final int MODE_OFF = 3;

    public static volatile MacroService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    private volatile boolean stopRequested = false;
    private Toast lastToast;

    private interface Finder {
        AccessibilityNodeInfo find();
    }

    private static class Result {
        static final int OK = 0, SKIP = 1, FAIL = 2;
        final int kind;
        final String msg;

        Result(int kind, String msg) {
            this.kind = kind;
            this.msg = msg;
        }

        static Result ok(String m) { return new Result(OK, m); }
        static Result skip(String m) { return new Result(SKIP, m); }
        static Result fail(String m) { return new Result(FAIL, m); }
    }

    // ---------------------------------------------------------------- 생명주기

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    /** 실행 중 볼륨 아래 버튼 = 중지. */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (running && event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                stopRequested = true;
                toast("중지합니다 — 지금 기기까지만 하고 멈춰요");
            }
            return true;
        }
        return false;
    }

    public boolean isRunning() {
        return running;
    }

    public static String modeLabel(int mode) {
        switch (mode) {
            case MODE_ON: return "켜기";
            case MODE_RUN: return "실행";
            case MODE_OFF: return "끄기";
            default: return "?";
        }
    }

    // ---------------------------------------------------------------- 실행

    public synchronized boolean start(final int mode) {
        if (running) return false;
        running = true;
        stopRequested = false;
        new Thread(() -> {
            String report;
            try {
                report = runAll(mode);
            } catch (Throwable t) {
                report = "오류가 발생했어요: " + t;
            } finally {
                running = false;
            }
            showInApp(report, null);
        }, "ceragem-macro").start();
        return true;
    }

    private String runAll(int mode) {
        Config c = Config.load(this);
        List<Integer> devices = c.selectedList();
        if (devices.isEmpty()) return "선택된 기기가 없어요.";

        StringBuilder lines = new StringBuilder();
        int ok = 0, skip = 0, fail = 0;
        boolean relaunch = true; // 처음 한 번만 앱을 새로 연다
        for (int k = 0; k < devices.size(); k++) {
            if (stopRequested) {
                lines.append("⏹ 사용자가 중지함\n");
                break;
            }
            String name = c.deviceName(devices.get(k));
            toast((k + 1) + "/" + devices.size() + "  " + name + "  " + modeLabel(mode) + " 중…");
            Result r = doDevice(c, mode, name, relaunch);
            if (r.kind == Result.FAIL && !stopRequested) {
                toast(name + " 다시 시도");
                r = doDevice(c, mode, name, true);
            }
            // 목록으로 빨리 돌아왔으면 다음 기기는 앱을 다시 열지 않는다
            relaunch = stopRequested || !returnToList(c);
            if (r.kind == Result.OK) ok++;
            else if (r.kind == Result.SKIP) skip++;
            else fail++;
            lines.append(r.kind == Result.OK ? "✅ " : r.kind == Result.SKIP ? "➖ " : "❌ ")
                    .append(name).append(" — ").append(r.msg).append('\n');
        }
        String when = new SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(new Date());
        return "[" + when + "] 전체 " + modeLabel(mode) + "\n"
                + "성공 " + ok + " · 건너뜀 " + skip + " · 실패 " + fail + "\n\n"
                + lines;
    }

    private Result doDevice(Config c, int mode, String name, boolean relaunch) {
        if (relaunch && !launchTarget()) return Result.fail("세라젬 IoT 앱을 열 수 없음");

        AccessibilityNodeInfo card = findCard(c, name, relaunch);
        if (stopRequested) return Result.fail("중지됨");
        if (card == null) return Result.fail("목록에서 기기를 찾지 못함");

        String status = collectText(card);
        boolean off = status.contains("전원 꺼짐");
        boolean usable = status.contains("사용 가능");
        if (mode == MODE_ON) {
            if (!off && usable) return Result.skip("이미 켜져 있음");
            if (!off) return Result.fail("상태 확인 불가 (" + clean(status) + ")");
        } else if (mode == MODE_OFF) {
            if (off) return Result.skip("이미 꺼져 있음");
            if (!usable) return Result.fail("상태 확인 불가 (" + clean(status) + ")");
        } else {
            if (off) return Result.fail("전원이 꺼져 있음 — 먼저 켜기");
        }

        if (!clickNode(card)) return Result.fail("기기 카드를 누르지 못함");

        AccessibilityNodeInfo power = waitFor(() -> findById(c.powerId), 12000);
        if (power == null) return Result.fail("기기 화면이 열리지 않음");

        if (mode == MODE_ON || mode == MODE_OFF) {
            sleep(500);
            if (!clickNode(power)) return Result.fail("전원 버튼을 누르지 못함");
            sleep(1200);
            return Result.ok(mode == MODE_ON ? "켰음" : "껐음");
        }
        return runMassage(c);
    }

    private Result runMassage(Config c) {
        sleep(1200);
        if (findExactText("마사지 중") != null) return Result.skip("이미 작동 중");

        String mode = c.modeName == null ? "" : c.modeName.trim();
        if (!mode.isEmpty() && !selectMode(mode)) {
            return Result.fail("'" + mode + "' 모드를 찾지 못함");
        }

        boolean pressed = false;
        AccessibilityNodeInfo play = findPlay(c);
        if (play != null) pressed = clickNode(play);
        if (!pressed) {
            Rect guess = playGuess();
            if (guess == null) return Result.fail("▶ 버튼을 찾지 못함");
            pressed = tap(guess.centerX(), guess.centerY());
        }
        if (!pressed) return Result.fail("▶ 버튼을 누르지 못함");

        AccessibilityNodeInfo started = waitFor(() -> findExactText("마사지 중"), 7000);
        if (started != null) return Result.ok((mode.isEmpty() ? "" : mode + " ") + "작동 시작");
        return Result.fail("▶를 눌렀지만 '마사지 중'이 확인되지 않음");
    }

    // ---------------------------------------------------------------- 세라젬 화면 조작

    private boolean launchTarget() {
        Intent i = getPackageManager().getLaunchIntentForPackage(Config.TARGET_PKG);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            return false;
        }
        sleep(1500);
        return true;
    }

    /** 목록이 뜰 때까지 기다린 뒤, 필요하면 스크롤하면서 기기 카드를 찾는다. */
    private AccessibilityNodeInfo findCard(Config c, String name, boolean fresh) {
        final String head = c.prefix.trim();
        AccessibilityNodeInfo any = waitFor(() -> {
            if (fresh) dismissPopups();
            return findTextStartingWith(head);
        }, fresh ? 25000 : 4000);
        if (any == null) return null;
        sleep(fresh ? 1500 : 300); // 기기 상태 글자가 새로 고쳐질 시간

        for (int i = 0; i < 12 && !stopRequested; i++) {
            AccessibilityNodeInfo t = findExactText(name);
            if (t != null && t.isVisibleToUser()) {
                sleep(400);
                AccessibilityNodeInfo again = findExactText(name);
                return containerOf(again != null ? again : t);
            }
            if (!scrollForward()) swipeListUp();
            sleep(900);
        }
        AccessibilityNodeInfo t = findExactText(name);
        return t == null ? null : containerOf(t);
    }

    /** 기기 화면에서 뒤로 가서 목록이 다시 보이면 true. */
    private boolean returnToList(Config c) {
        final String head = c.prefix.trim();
        if (findById(c.powerId) == null && findTextStartingWith(head) != null) return true;
        performGlobalAction(GLOBAL_ACTION_BACK);
        AccessibilityNodeInfo list = waitFor(() ->
                findById(c.powerId) == null ? findTextStartingWith(head) : null, 4000);
        return list != null;
    }

    private void dismissPopups() {
        String[] labels = {"오늘 하루 보지 않기", "다시 보지 않기", "닫기"};
        for (String l : labels) {
            AccessibilityNodeInfo n = findExactText(l);
            if (n != null) {
                clickNode(n);
                sleep(500);
            }
        }
    }

    private boolean selectMode(String mode) {
        // 1) 왼쪽으로 밀며(다음 모드) 찾기 → 2) 못 찾으면 반대 방향으로
        for (int dir = 0; dir < 2; dir++) {
            int tries = dir == 0 ? 6 : 12;
            for (int i = 0; i <= tries && !stopRequested; i++) {
                if (isModeCentered(mode)) return true;
                if (i == tries) break;
                swipeCarousel(dir == 0);
                sleep(1300);
            }
        }
        return isModeCentered(mode);
    }

    private boolean isModeCentered(String mode) {
        Rect screen = screenRect();
        if (screen == null) return false;
        for (AccessibilityNodeInfo n : findAllExactText(mode)) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (n.isVisibleToUser() && Math.abs(r.centerX() - screen.centerX()) < screen.width() * 0.18) {
                return true;
            }
        }
        return false;
    }

    private void swipeCarousel(boolean toNext) {
        Rect screen = screenRect();
        if (screen == null) return;
        int y = (int) (screen.top + screen.height() * 0.3);
        AccessibilityNodeInfo anchor = statusAnchor();
        if (anchor != null) {
            Rect r = new Rect();
            anchor.getBoundsInScreen(r);
            y = r.centerY();
        }
        int left = (int) (screen.left + screen.width() * 0.18);
        int right = (int) (screen.left + screen.width() * 0.82);
        if (toNext) swipe(right, y, left, y, 300);
        else swipe(left, y, right, y, 300);
    }

    /** 원 안의 "마사지 대기" 글자. ▶ 버튼 위치와 모드 넘기기 높이의 기준점. */
    private AccessibilityNodeInfo statusAnchor() {
        AccessibilityNodeInfo n = findExactText("마사지 대기");
        if (n == null) n = findExactText("마사지 중");
        return n;
    }

    private AccessibilityNodeInfo findPlay(Config c) {
        if (c.playId != null && !c.playId.trim().isEmpty()) {
            AccessibilityNodeInfo byId = findById(c.playId.trim());
            if (byId != null) return byId;
        }
        AccessibilityNodeInfo anchor = statusAnchor();
        Rect screen = screenRect();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (anchor == null || screen == null || root == null) return null;
        Rect a = new Rect();
        anchor.getBoundsInScreen(a);

        AccessibilityNodeInfo best = null;
        int bestDist = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo n : allNodes(root)) {
            if (!n.isClickable() || !n.isVisibleToUser()) continue;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.width() > screen.width() * 0.3) continue;            // 원 전체 같은 큰 영역 제외
            if (Math.abs(r.centerX() - a.centerX()) > screen.width() * 0.12) continue;
            if (r.top < a.bottom - 10 || r.top > a.bottom + screen.height() * 0.15) continue;
            int d = r.top - a.bottom;
            if (d < bestDist) {
                bestDist = d;
                best = n;
            }
        }
        return best;
    }

    private Rect playGuess() {
        AccessibilityNodeInfo anchor = statusAnchor();
        if (anchor == null) return null;
        Rect a = new Rect();
        anchor.getBoundsInScreen(a);
        int cy = a.bottom + a.height();
        return new Rect(a.centerX() - 10, cy - 10, a.centerX() + 10, cy + 10);
    }

    // ---------------------------------------------------------------- 화면 분석(덤프)

    public void dumpLater(int seconds) {
        toast(seconds + "초 뒤에 화면을 분석해요. 세라젬 앱 화면을 띄워 두세요.");
        main.postDelayed(() -> new Thread(this::doDump, "dump").start(), seconds * 1000L);
    }

    private void doDump() {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        StringBuilder sb = new StringBuilder();
        sb.append("# 세라젬 화면 분석 ").append(ts).append('\n');
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            sb.append("(화면을 읽지 못했어요)\n");
        } else {
            sb.append("package=").append(root.getPackageName()).append('\n');
            sb.append("표시: C=누를 수 있음 S=스크롤 V=화면에 보임\n\n");
            dumpNode(root, 0, sb);
        }
        String text = sb.toString();
        String saved = saveToDownloads("ceragem_dump_" + ts + ".txt", text);
        toast(saved != null ? "분석 완료: " + saved : "분석 완료");
        showInApp(null, text);
    }

    private void dumpNode(AccessibilityNodeInfo n, int depth, StringBuilder sb) {
        if (n == null || depth > 40) return;
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        for (int i = 0; i < depth; i++) sb.append("  ");
        CharSequence cls = n.getClassName();
        String c = cls == null ? "?" : cls.toString();
        int dot = c.lastIndexOf('.');
        sb.append(dot >= 0 ? c.substring(dot + 1) : c);
        if (n.getViewIdResourceName() != null) sb.append(" id=").append(n.getViewIdResourceName());
        if (n.getText() != null) sb.append(" text=\"").append(clean(n.getText().toString())).append('"');
        if (n.getContentDescription() != null) sb.append(" desc=\"").append(clean(n.getContentDescription().toString())).append('"');
        StringBuilder flags = new StringBuilder();
        if (n.isClickable()) flags.append('C');
        if (n.isScrollable()) flags.append('S');
        if (n.isVisibleToUser()) flags.append('V');
        if (flags.length() > 0) sb.append(" [").append(flags).append(']');
        sb.append(' ').append(b.toShortString()).append('\n');
        for (int i = 0; i < n.getChildCount(); i++) dumpNode(n.getChild(i), depth + 1, sb);
    }

    private String saveToDownloads(String fileName, String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return null;
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os == null) return null;
                    os.write(data);
                }
                return "Download/" + fileName;
            } else {
                File dir = getExternalFilesDir(null);
                if (dir == null) return null;
                File f = new File(dir, fileName);
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    fos.write(data);
                }
                return f.getAbsolutePath();
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 노드 도우미

    private AccessibilityNodeInfo waitFor(Finder f, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end && !stopRequested) {
            AccessibilityNodeInfo n = null;
            try {
                n = f.find();
            } catch (Exception ignored) {
            }
            if (n != null) return n;
            sleep(400);
        }
        return null;
    }

    private static String norm(CharSequence s) {
        return s == null ? "" : s.toString().replaceAll("\\s+", "");
    }

    private static String clean(String s) {
        return s.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private List<AccessibilityNodeInfo> findAllExactText(String text) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return out;
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(text);
        if (found == null) return out;
        String want = norm(text);
        for (AccessibilityNodeInfo n : found) {
            if (norm(n.getText()).equals(want) || norm(n.getContentDescription()).equals(want)) out.add(n);
        }
        return out;
    }

    private AccessibilityNodeInfo findExactText(String text) {
        AccessibilityNodeInfo fallback = null;
        for (AccessibilityNodeInfo n : findAllExactText(text)) {
            if (n.isVisibleToUser()) return n;
            if (fallback == null) fallback = n;
        }
        return fallback;
    }

    private AccessibilityNodeInfo findTextStartingWith(String head) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(head);
        if (found == null) return null;
        String want = norm(head);
        for (AccessibilityNodeInfo n : found) {
            if (norm(n.getText()).startsWith(want)) return n;
        }
        return null;
    }

    private AccessibilityNodeInfo findById(String id) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || id == null || id.isEmpty()) return null;
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(id);
        if (found == null || found.isEmpty()) return null;
        for (AccessibilityNodeInfo n : found) if (n.isVisibleToUser()) return n;
        return found.get(0);
    }

    private List<AccessibilityNodeInfo> allNodes(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty() && out.size() < 3000) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            out.add(n);
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo ch = n.getChild(i);
                if (ch != null) q.add(ch);
            }
        }
        return out;
    }

    private AccessibilityNodeInfo containerOf(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 6 && cur != null; i++) {
            if (cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        AccessibilityNodeInfo p = n.getParent();
        return p != null ? p : n;
    }

    private String collectText(AccessibilityNodeInfo n) {
        StringBuilder sb = new StringBuilder();
        for (AccessibilityNodeInfo x : allNodes(n)) {
            if (x.getText() != null) sb.append(x.getText()).append(' ');
        }
        return sb.toString();
    }

    private boolean clickNode(AccessibilityNodeInfo n) {
        if (n == null) return false;
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 6 && cur != null; i++) {
            if (cur.isClickable()) {
                if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                break;
            }
            cur = cur.getParent();
        }
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        return tap(r.centerX(), r.centerY());
    }

    private boolean scrollForward() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo best = null;
        long bestArea = 0;
        for (AccessibilityNodeInfo n : allNodes(root)) {
            if (!n.isScrollable()) continue;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            long area = (long) r.width() * r.height();
            if (area > bestArea) {
                bestArea = area;
                best = n;
            }
        }
        return best != null && best.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
    }

    private void swipeListUp() {
        Rect s = screenRect();
        if (s == null) return;
        int x = s.centerX();
        swipe(x, (int) (s.top + s.height() * 0.78), x, (int) (s.top + s.height() * 0.3), 350);
    }

    private Rect screenRect() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        Rect r = new Rect();
        root.getBoundsInScreen(r);
        return r.isEmpty() ? null : r;
    }

    // ---------------------------------------------------------------- 제스처

    private boolean tap(int x, int y) {
        Path p = new Path();
        p.moveTo(x, y);
        return gesture(new GestureDescription.StrokeDescription(p, 0, 60));
    }

    private boolean swipe(int x1, int y1, int x2, int y2, long ms) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return gesture(new GestureDescription.StrokeDescription(p, 0, ms));
    }

    private boolean gesture(GestureDescription.StrokeDescription stroke) {
        GestureDescription g = new GestureDescription.Builder().addStroke(stroke).build();
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {false};
        boolean dispatched = dispatchGesture(g, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                ok[0] = true;
                latch.countDown();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                latch.countDown();
            }
        }, null);
        if (!dispatched) return false;
        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return ok[0];
    }

    // ---------------------------------------------------------------- 기타

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private void toast(final String msg) {
        main.post(() -> {
            if (lastToast != null) lastToast.cancel();
            lastToast = Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT);
            lastToast.show();
        });
    }

    private void showInApp(String report, String dump) {
        if (report != null) MainActivity.lastReport = report;
        if (dump != null) MainActivity.lastDump = dump;
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra(MainActivity.EXTRA_SCROLL_BOTTOM, true);
        try {
            startActivity(i);
        } catch (Exception ignored) {
        }
    }
}
