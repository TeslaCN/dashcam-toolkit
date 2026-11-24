package icu.wwj.drive.stitch;

import android.net.Uri;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一次录制的一组多视角文件 */
public class VideoGroup {
    static final String[] PREFERRED = {"MAIN", "FRONT", "LEFT", "BACK", "REAR", "RIGHT"};

    public String key;
    public String title;
    public long durationMs;
    public long sortKey;
    public final Map<String, Uri> views = new LinkedHashMap<>();

    public int size() {
        return views.size();
    }

    /** 主视角排最前，其余按 PREFERRED 顺序 */
    public List<Uri> urisInOrder() {
        List<Uri> out = new ArrayList<>();
        for (String p : PREFERRED) {
            Uri u = views.get(p);
            if (u != null) out.add(u);
        }
        for (Uri u : views.values()) {
            if (!out.contains(u)) out.add(u);
        }
        return out;
    }

    public Uri[] orderedUris() {
        List<Uri> l = urisInOrder();
        return l.toArray(new Uri[0]);
    }

    public String viewSummary() {
        StringBuilder sb = new StringBuilder();
        for (String k : views.keySet()) {
            if (sb.length() > 0) sb.append('/');
            sb.append(k);
        }
        return sb.toString();
    }
}
