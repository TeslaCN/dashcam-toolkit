import icu.wwj.drive.dashcam.PlateAeController;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;

/**
 * 把一段 960×540 的原始 YUV420p 帧序列喂给 PlateAeController，打印每帧的
 * 场景分类、车牌代理块数量、代理亮度、道路高光等，用于离线回归/调参。
 *
 * 用法：java -cp build ClipStats <file.raw> <width> <height> [maxFrames]
 * 原始帧生成见 README.md。
 */
public class ClipStats {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: ClipStats <file.raw> <width> <height> [maxFrames]");
            return;
        }
        String path = args[0];
        int w = Integer.parseInt(args[1]);
        int h = Integer.parseInt(args[2]);
        int max = args.length > 3 ? Integer.parseInt(args[3]) : Integer.MAX_VALUE;
        int ySize = w * h;
        int uvSize = ySize / 4;
        int frameSize = ySize + uvSize * 2;
        PlateAeController controller = new PlateAeController();
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(path)))) {
            byte[] buf = new byte[frameSize];
            int f = 0;
            while (f < max) {
                int n = in.read(buf);
                if (n < frameSize) break;
                byte[] y = new byte[ySize];
                System.arraycopy(buf, 0, y, 0, ySize);
                byte[] u = new byte[uvSize];
                System.arraycopy(buf, ySize, u, 0, uvSize);
                byte[] v = new byte[uvSize];
                System.arraycopy(buf, ySize + uvSize, v, 0, uvSize);
                PlateAeController.Input i = new PlateAeController.Input();
                i.y = y;
                i.width = w;
                i.height = h;
                i.rowStride = w;
                i.pixelStride = 1;
                i.u = u;
                i.v = v;
                i.uvRowStride = w / 2;
                i.uvPixelStride = 1;
                i.exposureNs = 16_666_667L;
                i.iso = 3200;
                i.speedKph = 30f;
                i.nowMs = f * 1000L;
                i.fps = 30;
                PlateAeController.Output o = controller.onFrame(i);
                System.out.printf("%d scene=%s amb=%.0f sceneLuma=%.0f blocks=%d p90=%.0f clip=%.1f roadP90=%.0f%n",
                        f, PlateAeController.sceneName(o.scene), o.ambient, o.sceneLuma,
                        o.plateBlocks, o.plateP90, o.plateClipPct, o.roadP90);
                f++;
            }
        }
    }
}
