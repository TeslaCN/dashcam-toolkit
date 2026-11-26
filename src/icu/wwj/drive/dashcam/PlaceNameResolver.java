package icu.wwj.drive.dashcam;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.util.Log;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 道路名解析（方案 A，不判断高架/地面层）。
 *
 * <p>平台 Geocoder 是 2D 最近地址；高架与地面重叠时它无法告诉我们当前在哪一层。
 * 因此不做"高架优先"、也不猜层，只把匹配到的第 1 条地址里**它自己**的字段拆开：
 * featureName/地址行里的高架/高速名 + thoroughfare 地面道路名，显示成双候选，
 * 例如 "内环高架路 / 中山南二路"。
 *
 * <p>限制：北横通道的部分路段平台 Geocoder 不返回高架名，此时只能显示地面道路名；
 * 要补全需要第二数据源（高德/百度 key 或离线道路库），本类不做在线请求。
 */
final class PlaceNameResolver {

    private static final String TAG = "DashcamGeo";

    /** 国家/省级高速编号，例如 G2京沪高速（优先于通用匹配，避免带上"江桥镇"前缀）。 */
    private static final Pattern EXPRESS_CODE = Pattern.compile(
            "([GS]\\d+[\\u4e00-\\u9fa5]{0,8}(?:高速|快速路|公路))");

    /** 高架/高速/快速路名；尽量具体，避免把普通道路名当高架。 */
    private static final Pattern ELEVATED = Pattern.compile(
            "(北横通道|内环高架路|中环路|外环路|郊环线|延安高架路|南北高架路|沪闵高架路"
                    + "|逸仙高架路|华夏高架路|罗山高架路|京沪高速|沪宁高速|沪嘉高速|沪常高速|申嘉湖高速"
                    + "|[\\u4e00-\\u9fa5A-Za-z0-9]{2,10}(?:高架路|高架|通道|高速公路|高速|快速路|环路|环线))");

    private PlaceNameResolver() {
    }

    static final class Result {
        String display = "";
        String groundFull = "";
        String groundRoad = "";
        String elevated = "";
        String platformRaw = "";
    }

    static Result resolve(Context ctx, double lat, double lon) {
        Result r = new Result();
        List<Address> addrs = null;
        try {
            Geocoder g = new Geocoder(ctx, Locale.CHINA);
            addrs = g.getFromLocation(lat, lon, 5);
        } catch (Throwable t) {
            Log.w(TAG, "geocoder failed: " + t);
        }
        if (addrs == null || addrs.isEmpty()) return r;

        Address a0 = addrs.get(0);
        r.platformRaw = String.valueOf(a0.getAddressLine(0));
        r.groundFull = groundName(a0);
        r.groundRoad = a0.getThoroughfare() != null && !a0.getThoroughfare().isEmpty()
                ? a0.getThoroughfare() : a0.getFeatureName();
        // 只解析匹配到的第 1 条地址自身，不跨候选强行挑高架
        r.elevated = elevatedFrom(a0);

        if (r.elevated != null && r.groundRoad != null && !r.groundRoad.isEmpty()
                && !r.groundRoad.contains(r.elevated) && !r.elevated.contains(r.groundRoad)) {
            r.display = r.elevated + " / " + r.groundRoad;
        } else if (r.elevated != null) {
            r.display = r.elevated;
        } else if (r.groundFull != null) {
            r.display = r.groundFull;
        }
        return r;
    }

    /** 市+区+街道；没有街道时退回 featureName。 */
    private static String groundName(Address a) {
        String road = a.getThoroughfare();
        if (road == null || road.isEmpty()) road = a.getFeatureName();
        StringBuilder sb = new StringBuilder();
        if (a.getLocality() != null) sb.append(a.getLocality());
        if (a.getSubLocality() != null && !sb.toString().contains(a.getSubLocality()))
            sb.append(a.getSubLocality());
        if (road != null && !sb.toString().contains(road)) sb.append(road);
        return sb.toString();
    }

    private static String elevatedFrom(Address a) {
        String[] raws = {a.getFeatureName(), a.getAddressLine(0), a.getThoroughfare()};
        for (String raw : raws) {
            if (raw == null) continue;
            Matcher code = EXPRESS_CODE.matcher(raw);
            if (code.find()) return code.group(1);
            Matcher m = ELEVATED.matcher(raw);
            if (m.find()) return m.group(1);
        }
        return null;
    }
}
