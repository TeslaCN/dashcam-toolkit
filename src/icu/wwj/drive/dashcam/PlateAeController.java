package icu.wwj.drive.dashcam;

/**
 * 车牌优先的自适应曝光控制器。
 *
 * <p>设计依据（本机实测）：
 * <ul>
 *   <li>P30 的 Camera2 AE 补偿只有 ±2EV、步进 0.5EV，最多 1 个 AE 区域；</li>
 *   <li>没有可用的标准 HDR 场景模式，第三方拿不到厂商 HDR 视频；</li>
 *   <li>固定 EV 无法同时覆盖夜间（车牌/车灯过曝）和白天（车牌欠曝）。</li>
 * </ul>
 *
 * <p>因此用 {@code CONTROL_AE_MODE_OFF} + 逐帧曝光/ISO 做闭环。测光帧是 960×540 的 YUV：
 * 把下方道路区域切成小块，挑出"高亮度 + 高内部对比（字符结构）"的块作为车牌代理
 * （车牌是逆反射的亮色矩形 + 深色字符，这是它在夜间最显著的特征），对这些块做
 * 目标亮度闭环；没有这样的块时退回"路面高光加权"测光，避免被车灯/天空带偏。
 *
 * <p>控制优先级：
 * <ol>
 *   <li>快门：受车速限制（运动模糊），白天尽量短、夜间尽量长但不超过模糊上限；</li>
 *   <li>ISO：补足快门到边界后的剩余误差，夜间可到 6400，白天尽量低；</li>
 *   <li>过曝保护：车牌代理块过曝时先降 ISO（不增加模糊），ISO 到底再缩快门。</li>
 * </ol>
 *
 * <p>本类只依赖 {@code byte[]}，不 import Android API，便于在主机上离线仿真。
 */
public final class PlateAeController {

    public static final int SCENE_INIT = 0;
    public static final int SCENE_DAY = 1;
    public static final int SCENE_DUSK = 2;
    public static final int SCENE_NIGHT = 3;
    public static final int SCENE_TUNNEL = 4;

    public static String sceneName(int s) {
        switch (s) {
            case SCENE_DAY: return "DAY";
            case SCENE_DUSK: return "DUSK";
            case SCENE_NIGHT: return "NIGHT";
            case SCENE_TUNNEL: return "TUNNEL";
            default: return "INIT";
        }
    }

    public static final class Input {
        public byte[] y, u, v;
        public int width, height, rowStride, pixelStride;
        public int uvRowStride, uvPixelStride;
        public long nowMs;
        public long exposureNs;
        public int iso;
        public float speedKph;
        public int fps = 30;
        public int isoMin = 50, isoMax = 6400;
    }

    public static final class Output {
        public long exposureNs;
        public int iso;
        public boolean changed;
        public int scene;
        /** 被选中的"车牌代理块"数量。 */
        public int plateBlocks;
        public double plateP50, plateP90, plateClipPct, plateContrast;
        public int boxX0 = -1, boxY0 = -1, boxX1 = -1, boxY1 = -1;
        public double ambient, sceneLuma, roadP50, roadP90, roadClipPct;
        public double target, errorEv;
        public String note = "";
    }

    // 目标：代理块 90 分位亮度
    private static final double TARGET_DAY = 218;
    private static final double TARGET_DUSK = 205;
    private static final double TARGET_NIGHT = 198;
    private static final double TARGET_TUNNEL = 205;
    private static final double TARGET_MIN_DAY = 180;
    private static final double TARGET_MIN_NIGHT = 160;

    private static final double ROAD_TARGET_DAY = 232;
    private static final double ROAD_TARGET_DUSK = 222;
    private static final double ROAD_TARGET_NIGHT = 208;

    private static final double CLIP_LEVEL = 246;

    /**
     * 白天允许的快门档位：短曝光优先，保证运动车牌不糊。
     * 50Hz 照明下这些短曝光可能有轻微条纹，但白天信噪比高、可接受。
     */
    private static final long[] SHUTTER_DAY = {
            500_000L, 1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L, 10_000_000L,
    };

    /**
     * 夜间/黄昏只允许 50Hz 安全档：手动 AE 下 antibanding 不生效，
     * 曝光时间必须是 10ms 的整数倍（10/20/30ms），否则路灯/隧道灯会出现滚动条纹。
     * 代价是夜间最快只有 1/100s，横穿/对向车牌的拖影无法消除（HDR 不可用的物理上限）。
     */
    private static final long[] SHUTTER_NIGHT = {
            10_000_000L, 20_000_000L, 30_000_000L,
    };

    private int scene = SCENE_INIT;
    private double ambientEma = -1;
    private long transitionUntilMs;
    private long lastControlMs;
    private long lastSceneMs;
    private long lastExposureNs;
    private int lastIso;
    private int sceneStableCount;
    private double lastErrorEv;
    private int flipCount;
    private boolean seeded;

    // 测光网格（step=2）+ 块统计
    private int gw, gh;
    private int[] gY, gU, gV;
    private int[] hist;

    private static final int BLOCK = 8;          // 8x8 grid px = 16x16 source px
    private int bw, bh;                          // 块数量
    private int[] bP90, bP50, bStd;
    private short[] bClip;                       // 千分比裁剪
    private int[] bX0, bY0;                      // 块左上角（grid）

    public PlateAeController() {
    }

    public int scene() {
        return scene;
    }

    // ------------------------------------------------------------------
    // 主入口
    // ------------------------------------------------------------------

    public Output onFrame(Input in) {
        Output out = new Output();
        out.scene = scene;
        out.exposureNs = in.exposureNs;
        out.iso = in.iso;
        if (in.y == null || in.width < 32 || in.height < 32) return out;

        this.nowMs = in.nowMs;
        lastExposureNs = in.exposureNs;
        lastIso = in.iso;
        buildGrid(in);
        computeRoadStats(out);
        updateScene(in.nowMs);

        analyzeHighlights(out);
        boolean havePlate = out.plateBlocks > 0;
        boolean transitioning = in.nowMs < transitionUntilMs;

        double target, errorEv;
        if (havePlate) {
            target = targetFor(scene, true);
            double measured = out.plateP90;
            errorEv = log2(target / Math.max(10, measured));
            if (out.plateClipPct > 2.0) errorEv = Math.min(errorEv, transitioning ? -1.2 : -0.8);
            else if (out.plateClipPct > 0.5) errorEv = Math.min(errorEv, -0.25);
            if (measured < targetFor(scene, false) && out.plateClipPct < 0.1) {
                errorEv = Math.max(errorEv, 0.35);
            }
            out.note = "plate";
        } else {
            target = roadTargetFor(scene);
            double measured = out.roadP90;
            errorEv = log2(target / Math.max(10, measured));
            if (out.roadClipPct > 12.0) errorEv = Math.min(errorEv, -0.6);
            else if (out.roadClipPct > 5.0) errorEv = Math.min(errorEv, -0.2);
            // 夜间无代理块时不允许连续大幅下压，避免"越压越黑、回不来"
            if (scene == SCENE_NIGHT || scene == SCENE_DUSK) errorEv = Math.max(errorEv, -0.45);
            out.note = "road";
        }
        out.target = target;
        out.errorEv = errorEv;

        int plateBlocks = out.plateBlocks;

        if (in.nowMs - lastControlMs < 120) {
            out.note += "/hold";
            return out;
        }

        if (Math.abs(errorEv) < 0.10) {
            out.note += "/deadband";
            return out;
        }
        double maxStep = transitioning ? 1.6 : 0.9;
        errorEv = clamp(errorEv, -maxStep, maxStep);
        if (lastErrorEv != 0 && Math.signum(errorEv) != Math.signum(lastErrorEv)) flipCount++;
        else flipCount = Math.max(0, flipCount - 1);
        if (flipCount >= 2) {
            errorEv *= 0.5;
            out.note += "/damp";
        }
        lastErrorEv = errorEv;

        long frameNs = 1_000_000_000L / Math.max(1, in.fps);
        long maxShutter = Math.min(frameNs, motionShutterCapNs(in.speedKph));
        long minShutter = Math.min(500_000L, maxShutter);
        if (scene == SCENE_NIGHT || scene == SCENE_DUSK || scene == SCENE_TUNNEL) {
            // 50Hz 照明（路灯/隧道灯）下手动 AE 必须用 10ms 整数倍：夜间快门下限锁 1/100。
            // 这会让横穿/对向车牌有拖影，属于无 HDR 机型的物理上限。
            minShutter = Math.min(10_000_000L, maxShutter);
            if (maxShutter < 10_000_000L) maxShutter = Math.min(frameNs, 10_000_000L);
        }
        int isoFloor = isoFloor(scene);
        int isoCeil = Math.max(isoFloor, Math.min(in.isoMax, 6400));

        if (!seeded) {
            seeded = true;
            long seedExp = quantizeShutter(seedExposureNs(scene), minShutter, maxShutter);
            int seedIso = clampIso(seedIso(scene), isoFloor, isoCeil);
            out.exposureNs = seedExp;
            out.iso = seedIso;
            out.changed = (seedExp != in.exposureNs || seedIso != in.iso);
            out.note += "/seed";
            lastControlMs = in.nowMs;
            return out;
        }

        long newExp = in.exposureNs;
        int newIso = in.iso;
        if (errorEv >= 0) {
            // 欠曝：先把快门加到运动模糊允许的上限，再抬 ISO
            double want = in.exposureNs * Math.pow(2, errorEv);
            newExp = quantizeShutter(want, minShutter, maxShutter);
            double residual = want / newExp;
            newIso = (int) Math.round(in.iso * clamp(residual, 1.0, 2.0));
        } else {
            // 过曝：先降 ISO（不增加运动模糊），ISO 到底再缩快门
            int targetIso = (int) Math.round(in.iso * Math.pow(2, errorEv));
            if (targetIso >= isoFloor) {
                newIso = targetIso;
            } else {
                newIso = isoFloor;
                double need = Math.pow(2, errorEv) * in.iso / isoFloor;
                newExp = quantizeShutter(in.exposureNs * need, minShutter, maxShutter);
                double residual = (in.exposureNs * need) / newExp;
                newIso = (int) Math.round(isoFloor * clamp(residual, 1.0, 2.0));
            }
        }
        newIso = clampIso(newIso, isoFloor, isoCeil);

        // 车牌代理块明显过曝：先缩短曝光（满阱饱和后降 ISO 不一定救得回来），
        // 快门已到下限才降 ISO。
        if (plateBlocks > 0 && out.plateClipPct > 2.0) {
            long shorter = quantizeShutter(newExp * 0.5, minShutter, maxShutter);
            if (shorter < newExp) {
                newExp = shorter;
            } else if (newIso > isoFloor) {
                newIso = Math.max(isoFloor, (int) (newIso * 0.7));
            }
            out.note += "/cutExp";
        }

        out.exposureNs = newExp;
        out.iso = newIso;
        out.changed = (newExp != in.exposureNs || newIso != in.iso);
        if (out.changed) lastControlMs = in.nowMs;
        return out;
    }

    // ------------------------------------------------------------------
    // 曝光策略
    // ------------------------------------------------------------------

    private double targetFor(int scene, boolean white) {
        switch (scene) {
            case SCENE_DAY: return white ? TARGET_DAY : TARGET_MIN_DAY;
            case SCENE_TUNNEL: return white ? TARGET_TUNNEL : TARGET_MIN_DAY;
            case SCENE_NIGHT: return white ? TARGET_NIGHT : TARGET_MIN_NIGHT;
            default: return white ? TARGET_DUSK : TARGET_MIN_DAY;
        }
    }

    private double roadTargetFor(int scene) {
        switch (scene) {
            case SCENE_DAY: return ROAD_TARGET_DAY;
            case SCENE_DUSK: return ROAD_TARGET_DUSK;
            default: return ROAD_TARGET_NIGHT;
        }
    }

    private int isoFloor(int scene) {
        switch (scene) {
            case SCENE_DAY: return 50;
            case SCENE_DUSK: return 100;
            default: return 200;
        }
    }

    private long seedExposureNs(int scene) {
        switch (scene) {
            case SCENE_DAY: return 2_000_000L;      // 1/500
            case SCENE_DUSK: return 8_000_000L;     // 1/125
            case SCENE_TUNNEL: return 10_000_000L;
            default: return 10_000_000L;            // 1/100：50Hz 安全档
        }
    }

    private int seedIso(int scene) {
        switch (scene) {
            case SCENE_DAY: return 100;
            case SCENE_DUSK: return 500;
            case SCENE_TUNNEL: return 800;
            default: return 2000;
        }
    }

    /**
     * 最长曝光估计。只看本车速度不够：横穿/对向/被超车的车牌相对速度更高，
     * 所以用 vEff = max(v, 15) + 0.5v 作为保守相对速度。
     * 夜间实际再被 50Hz 档位限制到 ≤1/100。
     */
    private long motionShutterCapNs(float kph) {
        double v = Math.max(0, kph);
        double vEff = Math.max(v, 15) + 0.5 * v;
        if (vEff < 15) return 33_333_333L;
        if (vEff < 30) return 10_000_000L;    // 1/100
        if (vEff < 50) return 4_000_000L;     // 1/250
        if (vEff < 80) return 2_000_000L;     // 1/500
        return 1_000_000L;                    // 1/1000
    }

    private long quantizeShutter(double wantNs, long minNs, long maxNs) {
        long[] table = (scene == SCENE_NIGHT || scene == SCENE_DUSK) ? SHUTTER_NIGHT : SHUTTER_DAY;
        long best = -1;
        for (long s : table) {
            if (s < minNs || s > maxNs) continue;
            if (best < 0 || Math.abs(s - wantNs) < Math.abs(best - wantNs)) best = s;
        }
        if (best < 0) best = wantNs < minNs ? minNs : maxNs;
        return best;
    }

    private static int clampIso(int iso, int lo, int hi) {
        return iso < lo ? lo : (iso > hi ? hi : iso);
    }

    // ------------------------------------------------------------------
    // 网格 / 场景统计
    // ------------------------------------------------------------------

    private void buildGrid(Input in) {
        int step = 2;
        int nw = Math.max(1, in.width / step);
        int nh = Math.max(1, in.height / step);
        if (gw != nw || gh != nh || gY == null) {
            gw = nw;
            gh = nh;
            gY = new int[gw * gh];
            gU = new int[gw * gh];
            gV = new int[gw * gh];
            hist = new int[256];
            bw = (gw + BLOCK - 1) / BLOCK;
            bh = (gh + BLOCK - 1) / BLOCK;
            bP90 = new int[bw * bh];
            bP50 = new int[bw * bh];
            bStd = new int[bw * bh];
            bClip = new short[bw * bh];
            bX0 = new int[bw * bh];
            bY0 = new int[bw * bh];
        }
        int ps = Math.max(1, in.pixelStride);
        int urs = Math.max(1, in.uvRowStride);
        int ups = Math.max(1, in.uvPixelStride);
        int yLimit = in.y.length;
        int uLimit = in.u == null ? 0 : in.u.length;
        int vLimit = in.v == null ? 0 : in.v.length;
        for (int gy = 0; gy < gh; gy++) {
            int sy = gy * step;
            int yRow = sy * in.rowStride;
            int uvRow = (sy / 2) * urs;
            for (int gx = 0; gx < gw; gx++) {
                int sx = gx * step;
                int offY = yRow + sx * ps;
                int idx = gy * gw + gx;
                gY[idx] = offY < yLimit ? (in.y[offY] & 0xFF) : 0;
                int offUV = uvRow + (sx / 2) * ups;
                gU[idx] = in.u != null && offUV < uLimit ? (in.u[offUV] & 0xFF) : 128;
                gV[idx] = in.v != null && offUV < vLimit ? (in.v[offUV] & 0xFF) : 128;
            }
        }
    }

    private void computeRoadStats(Output out) {
        int x0 = (int) (gw * 0.15), x1 = (int) (gw * 0.85);
        int y0 = (int) (gh * 0.40), y1 = (int) (gh * 0.92);
        int[] h = new int[256];
        int n = 0, clip = 0;
        for (int y = y0; y < y1; y++) {
            int row = y * gw;
            for (int x = x0; x < x1; x++) {
                int v = gY[row + x];
                h[v]++;
                n++;
                if (v >= CLIP_LEVEL) clip++;
            }
        }
        out.ambient = percentile(h, n, 0.50);
        out.roadP50 = out.ambient;
        out.roadP90 = percentile(h, n, 0.90);
        out.roadClipPct = n == 0 ? 0 : clip * 100.0 / n;

        // 曝光归一化亮度：把当前曝光/ISO 折算回 1/60s、ISO800 下的取值，
        // 这样场景分类不会被"我们刚刚改了曝光"带偏（否则会自激振荡）。
        double exp = Math.max(1, lastExposureNs);
        double iso = Math.max(1, lastIso);
        double norm = out.ambient * (16_666_667.0 / exp) * (800.0 / iso);
        out.sceneLuma = Math.max(0, Math.min(20000, norm));
        if (ambientEma < 0) ambientEma = out.sceneLuma;
        else {
            double jump = out.sceneLuma - ambientEma;
            if (Math.abs(jump) > 260) transitionUntilMs = nowMs + 900;
            ambientEma = 0.7 * ambientEma + 0.3 * out.sceneLuma;
        }
    }

    private long nowMs;   // 供 computeRoadStats 记录跳变时间

    private static double percentile(int[] h, int total, double p) {
        if (total <= 0) return 0;
        int want = (int) Math.round(total * p);
        if (want < 1) want = 1;
        int acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += h[i];
            if (acc >= want) return i;
        }
        return 255;
    }

    private void updateScene(long nowMs) {
        if (nowMs - lastSceneMs < 500 && scene != SCENE_INIT) return;
        lastSceneMs = nowMs;
        // 归一化亮度的阈值（1/60s、ISO800 基准）：
        //   夜间 < 80；黄昏 80–400；白天 > 400
        double a = ambientEma;
        int next;
        if (a < 80) next = SCENE_NIGHT;
        else if (a < 400) next = SCENE_DUSK;
        else next = SCENE_DAY;
        if (scene == SCENE_DAY && next != SCENE_DAY && nowMs < transitionUntilMs) next = SCENE_TUNNEL;
        if (scene == SCENE_INIT) {
            scene = next;
            sceneStableCount = 0;
            return;
        }
        if (next == scene) {
            sceneStableCount = 0;
            return;
        }
        sceneStableCount++;
        // 需要连续 3 次（≈1.5s）判断一致才切换，避免抖动
        if (sceneStableCount >= 3) {
            scene = next;
            sceneStableCount = 0;
        }
    }

    // ------------------------------------------------------------------
    // "车牌代理块"：高亮度 + 高内部对比（字符），排除均匀过曝的灯芯
    // ------------------------------------------------------------------

    private void analyzeHighlights(Output out) {
        int minP90 = scene == SCENE_NIGHT ? 150 : (scene == SCENE_DAY ? 170 : 160);
        int needStd = scene == SCENE_NIGHT ? 14 : 18;
        int bestCount = 0;
        long sumP90 = 0, sumP50 = 0;
        int clipPixels = 0, totalPixels = 0;
        int minX = gw, minY = gh, maxX = -1, maxY = -1;
        int[] tmp = new int[BLOCK * BLOCK];
        int[] h = new int[256];

        for (int by = 0; by < bh; by++) {
            for (int bx = 0; bx < bw; bx++) {
                int px0 = bx * BLOCK, py0 = by * BLOCK;
                int px1 = Math.min(gw - 1, px0 + BLOCK - 1);
                int py1 = Math.min(gh - 1, py0 + BLOCK - 1);
                int w = px1 - px0 + 1, hh = py1 - py0 + 1;
                int n = w * hh;
                if (n <= 0) continue;
                int idx = by * bw + bx;
                java.util.Arrays.fill(h, 0);
                int sum = 0, sum2 = 0, k = 0;
                for (int y = py0; y <= py1; y++) {
                    for (int x = px0; x <= px1; x++) {
                        int v = gY[y * gw + x];
                        sum += v;
                        sum2 += v * v;
                        if (k < tmp.length) tmp[k++] = v;
                        h[v]++;
                    }
                }
                double mean = sum / (double) n;
                double var = sum2 / (double) n - mean * mean;
                int std = (int) Math.sqrt(Math.max(0, var));
                int p90 = (int) percentile(h, n, 0.90);
                int p50 = (int) percentile(h, n, 0.50);
                int clip = 0;
                for (int v = (int) CLIP_LEVEL; v < 256; v++) clip += h[v];
                bP90[idx] = p90;
                bP50[idx] = p50;
                bStd[idx] = std;
                bClip[idx] = (short) (clip * 1000 / n);
                bX0[idx] = px0;
                bY0[idx] = py0;

                // 位置：只取下方道路区域
                double cy = (py0 + py1) * 0.5 / gh;
                if (cy < 0.38 || cy > 0.93) continue;
                if (p90 < minP90) continue;
                if (std < needStd) continue;
                // 均匀过曝的灯芯（整块白、几乎没有结构）不算车牌
                if (p90 >= 252 && std < 8) continue;
                // 内部必须有亮暗结构：p90-p50 至少 20
                if (p90 - p50 < 20) continue;

                bestCount++;
                sumP90 += p90;
                sumP50 += p50;
                clipPixels += clip;
                totalPixels += n;
                if (px0 < minX) minX = px0;
                if (py0 < minY) minY = py0;
                if (px1 > maxX) maxX = px1;
                if (py1 > maxY) maxY = py1;
            }
        }
        out.plateBlocks = bestCount;
        if (bestCount > 0) {
            out.plateP90 = sumP90 / (double) bestCount;
            out.plateP50 = sumP50 / (double) bestCount;
            out.plateClipPct = totalPixels == 0 ? 0 : clipPixels * 100.0 / totalPixels;
            out.plateContrast = out.plateP90 - out.plateP50;
            out.boxX0 = minX; out.boxY0 = minY; out.boxX1 = maxX; out.boxY1 = maxY;
        }
    }

    private static double log2(double v) {
        return Math.log(v) / Math.log(2.0);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
