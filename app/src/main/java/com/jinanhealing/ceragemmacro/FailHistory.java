package com.jinanhealing.ceragemmacro;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 실패 기록 모음. 실패·확인 안 됨·앱 재실행·네트워크 알림이 있었던 실행만 앱 안에 계속 쌓는다.
 * 파일이 너무 커지면 오래된 것부터 지운다.
 */
public final class FailHistory {
    private static final String FILE = "fail_history.txt";
    private static final int MAX_BYTES = 300_000;
    private static final String SEP = "\n════════════════════════════════\n";

    private FailHistory() {
    }

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE);
    }

    public static synchronized void append(Context ctx, String block) {
        try {
            String old = read(ctx);
            String all = old.isEmpty() ? block : old + SEP + block;
            byte[] data = all.getBytes(StandardCharsets.UTF_8);
            if (data.length > MAX_BYTES) {
                // 오래된 실행부터 잘라낸다
                String[] parts = all.split(Pattern.quote(SEP));
                StringBuilder sb = new StringBuilder();
                for (int i = parts.length - 1; i >= 0; i--) {
                    String add = sb.length() == 0 ? parts[i] : parts[i] + SEP + sb;
                    if (add.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) break;
                    sb.setLength(0);
                    sb.append(add);
                }
                all = sb.toString();
                data = all.getBytes(StandardCharsets.UTF_8);
            }
            try (FileOutputStream fos = new FileOutputStream(file(ctx), false)) {
                fos.write(data);
            }
        } catch (Exception ignored) {
        }
    }

    public static synchronized String read(Context ctx) {
        File f = file(ctx);
        if (!f.exists()) return "";
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int r = in.read(buf, off, buf.length - off);
                if (r < 0) break;
                off += r;
            }
            return new String(buf, 0, off, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    public static synchronized void clear(Context ctx) {
        //noinspection ResultOfMethodCallIgnored
        file(ctx).delete();
    }

    /** 저장된 실행(묶음) 수. */
    public static int count(Context ctx) {
        String s = read(ctx);
        if (s.isEmpty()) return 0;
        return s.split(Pattern.quote(SEP)).length;
    }

    /** 공유용: 맨 위에 기기별·원인별 집계를 붙인다. */
    public static String withSummary(Context ctx) {
        String s = read(ctx);
        if (s.isEmpty()) return "";
        Map<String, Integer> perDevice = new TreeMap<>();
        Map<String, Integer> perReason = new LinkedHashMap<>();
        Pattern p = Pattern.compile("^(❌|⚠️?|❔) (MASTER V9 \\d+) — (.*)$", Pattern.MULTILINE);
        Matcher m = p.matcher(s);
        while (m.find()) {
            perDevice.merge(m.group(2), 1, Integer::sum);
            String reason = m.group(3).replaceAll("^.*? / ", "").trim();
            if (reason.length() > 40) reason = reason.substring(0, 40) + "…";
            perReason.merge(reason, 1, Integer::sum);
        }
        int net = 0;
        Matcher nm = Pattern.compile("네트워크 알림 (\\d+)번").matcher(s);
        while (nm.find()) net += Integer.parseInt(nm.group(1));

        StringBuilder sb = new StringBuilder();
        sb.append("# 세라젬 일괄조작 실패 기록 모음 (").append(count(ctx)).append("회 실행)\n\n");
        sb.append("## 기기별 문제 횟수\n");
        if (perDevice.isEmpty()) sb.append("(없음)\n");
        for (Map.Entry<String, Integer> e : perDevice.entrySet()) {
            sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append("번\n");
        }
        sb.append("\n## 원인별 횟수\n");
        if (perReason.isEmpty()) sb.append("(없음)\n");
        for (Map.Entry<String, Integer> e : perReason.entrySet()) {
            sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append("번\n");
        }
        sb.append("\n네트워크 알림 합계: ").append(net).append("번\n");
        sb.append("\n## 실행별 기록\n\n").append(s);
        return sb.toString();
    }
}
