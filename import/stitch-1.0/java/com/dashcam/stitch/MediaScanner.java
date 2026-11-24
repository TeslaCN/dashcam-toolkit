package com.dashcam.stitch;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 扫描相册里的视频，按"文件名里的时间戳"把同一次录制的多视角聚合起来。
 * 约定: <前缀>_<MAIN|LEFT|BACK|RIGHT|FRONT|REAR>_<时间戳>.mp4
 */
public class MediaScanner {

    private static final String[] VIEW_TOKENS = {
            "MAIN", "FRONT", "LEFT", "BACK", "REAR", "RIGHT", "FRONTLEFT", "FRONTRIGHT"
    };

    public static class Result {
        public final List<VideoGroup> groups = new ArrayList<>();
        public int scanned = 0;
        public int matched = 0;
    }

    public static Result scan(ContentResolver cr) {
        Result r = new Result();
        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] proj = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.BUCKET_ID
        };
        Cursor c = null;
        try {
            c = cr.query(collection, proj, null, null,
                    MediaStore.Video.Media.DATE_ADDED + " DESC");
            if (c == null) return r;
            int idIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int nameIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int durIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);
            int bucketIdx = c.getColumnIndex(MediaStore.Video.Media.BUCKET_ID);
            java.util.LinkedHashMap<String, VideoGroup> map = new java.util.LinkedHashMap<>();
            while (c.moveToNext()) {
                r.scanned++;
                try {
                    String name = c.getString(nameIdx);
                    if (name == null) continue;
                    Parsed p = parse(name);
                    if (p == null) continue;
                    long bucket = bucketIdx >= 0 ? c.getLong(bucketIdx) : 0;
                    p.key = bucket + "#" + p.key;
                    long id = c.getLong(idIdx);
                    Uri uri = ContentUris.withAppendedId(collection, id);
                    VideoGroup g = map.get(p.key);
                    if (g == null) {
                        g = new VideoGroup();
                        g.key = p.key;
                        g.title = p.title;
                        g.sortKey = p.sortKey;
                        map.put(p.key, g);
                    }
                    if (g.views.containsKey(p.view)) continue;   // 同视角重复，保留第一份
                    g.views.put(p.view, uri);
                    long dur = c.getLong(durIdx);
                    if (dur > 0 && (g.durationMs == 0 || dur < g.durationMs)) g.durationMs = dur;
                    r.matched++;
                } catch (Exception perFile) {
                    // 单个文件名异常不影响整表
                }
            }
            for (VideoGroup g : map.values()) {
                // 必须包含主视角，否则底下的 LEFT/BACK/RIGHT 会被当成主画面拉伸
                boolean hasMain = g.views.containsKey("MAIN") || g.views.containsKey("FRONT");
                if (g.size() >= 2 && hasMain) r.groups.add(g);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        Collections.sort(r.groups, new Comparator<VideoGroup>() {
            @Override
            public int compare(VideoGroup a, VideoGroup b) {
                return Long.compare(b.sortKey, a.sortKey);
            }
        });
        return r;
    }

    static class Parsed {
        String view;
        String key;
        String title;
        long sortKey;
    }

    /** 解析文件名，失败返回 null（不是多视角素材） */
    static Parsed parse(String fileName) {
        try {
            return parse0(fileName);
        } catch (Exception e) {
            return null;
        }
    }

    private static Parsed parse0(String fileName) {
        String base = fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        String[] parts = base.split("[_\\-\\. ]+");
        String view = null;
        for (String part : parts) {
            for (String t : VIEW_TOKENS) {
                if (part.equalsIgnoreCase(t)) {
                    view = t;
                    break;
                }
            }
            if (view != null) break;
        }
        if (view == null) return null;

        int[] dt = extractDateTime(parts);
        if (dt == null) return null;
        Parsed p = new Parsed();
        p.view = view;
        p.key = String.format("%04d%02d%02d%02d%02d%02d%03d",
                dt[0], dt[1], dt[2], dt[3], dt[4], dt[5], dt.length > 6 ? dt[6] : 0);
        p.title = String.format("%04d-%02d-%02d %02d:%02d:%02d",
                dt[0], dt[1], dt[2], dt[3], dt[4], dt[5]);
        p.sortKey = ((long) dt[0] * 10000 + dt[1] * 100 + dt[2]) * 1000000L
                + dt[3] * 10000L + dt[4] * 100L + dt[5];
        return p;
    }

    /** 支持 2026_11_23_20_12_55_780 和 20261123_201255 两种写法 */
    private static int[] extractDateTime(String[] p) {
        for (int i = 0; i < p.length; i++) {
            if (p[i].length() == 4 && isNum(p[i])) {
                int y = Integer.parseInt(p[i]);
                if (y < 2000 || y > 2100) continue;
                if (i + 5 >= p.length) continue;
                if (p[i + 1].length() > 2 || p[i + 2].length() > 2 || p[i + 3].length() > 2
                        || p[i + 4].length() > 2 || p[i + 5].length() > 2) continue;
                if (!isNum(p[i + 1]) || !isNum(p[i + 2]) || !isNum(p[i + 3])
                        || !isNum(p[i + 4]) || !isNum(p[i + 5])) continue;
                int mo = Integer.parseInt(p[i + 1]);
                int d = Integer.parseInt(p[i + 2]);
                int hh = Integer.parseInt(p[i + 3]);
                int mm = Integer.parseInt(p[i + 4]);
                int ss = Integer.parseInt(p[i + 5]);
                if (mo < 1 || mo > 12 || d < 1 || d > 31 || hh > 23 || mm > 59 || ss > 59) continue;
                int ms = 0;
                if (i + 6 < p.length && isNum(p[i + 6]) && p[i + 6].length() <= 3) {
                    ms = Integer.parseInt(p[i + 6]);
                }
                return new int[]{y, mo, d, hh, mm, ss, ms};
            }
            if (p[i].length() == 8 && isNum(p[i]) && p[i].startsWith("20")) {
                int y = Integer.parseInt(p[i].substring(0, 4));
                int mo = Integer.parseInt(p[i].substring(4, 6));
                int d = Integer.parseInt(p[i].substring(6, 8));
                if (mo < 1 || mo > 12 || d < 1 || d > 31) continue;
                if (i + 1 >= p.length || p[i + 1].length() < 6 || !isNum(p[i + 1])) continue;
                String t = p[i + 1];
                int hh = Integer.parseInt(t.substring(0, 2));
                int mm = Integer.parseInt(t.substring(2, 4));
                int ss = Integer.parseInt(t.substring(4, 6));
                if (hh > 23 || mm > 59 || ss > 59) continue;
                return new int[]{y, mo, d, hh, mm, ss, 0};
            }
        }
        return null;
    }

    private static boolean isNum(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }
}
