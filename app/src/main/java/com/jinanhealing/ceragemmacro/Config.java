package com.jinanhealing.ceragemmacro;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** 사용자가 바꿀 수 있는 설정값. */
public class Config {
    public static final String TARGET_PKG = "kr.co.ceragem.iot";
    public static final String DEFAULT_POWER_ID = "kr.co.ceragem.iot:id/do_control_action";

    public int count = 10;
    public String prefix = "MASTER V9";
    public String modeName = "음파 진동 마사지1";
    public String playId = "";
    public String powerId = DEFAULT_POWER_ID;
    /** 0 안정 · 1 보통 · 2 빠름 */
    public int speed = 2;
    public Set<Integer> selected = new TreeSet<>();

    public static Config load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        Config c = new Config();
        c.count = Math.max(1, Math.min(30, p.getInt("count", 10)));
        c.prefix = p.getString("prefix", c.prefix);
        c.modeName = p.getString("modeName", c.modeName);
        c.playId = p.getString("playId", "");
        c.powerId = p.getString("powerId", DEFAULT_POWER_ID);
        c.speed = Math.max(0, Math.min(2, p.getInt("speed", 2)));
        String sel = p.getString("selected", null);
        c.selected = new TreeSet<>();
        if (sel == null) {
            for (int i = 1; i <= c.count; i++) c.selected.add(i);
        } else if (!sel.isEmpty()) {
            for (String s : sel.split(",")) {
                try {
                    c.selected.add(Integer.parseInt(s.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return c;
    }

    public void save(Context ctx) {
        StringBuilder sb = new StringBuilder();
        for (Integer i : selected) {
            if (sb.length() > 0) sb.append(',');
            sb.append(i);
        }
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
                .putInt("count", count)
                .putString("prefix", prefix)
                .putString("modeName", modeName)
                .putString("playId", playId)
                .putString("powerId", powerId)
                .putInt("speed", speed)
                .putString("selected", sb.toString())
                .apply();
    }

    public List<Integer> selectedList() {
        List<Integer> out = new ArrayList<>();
        for (int i = 1; i <= count; i++) if (selected.contains(i)) out.add(i);
        return out;
    }

    public String deviceName(int i) {
        return prefix.trim() + " " + String.format(Locale.US, "%02d", i);
    }
}
