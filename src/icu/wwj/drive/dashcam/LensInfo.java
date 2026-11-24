package icu.wwj.drive.dashcam;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.util.Log;
import android.util.Range;
import android.util.SizeF;

/** Computes each camera's equivalent focal length so digital zoom can be mapped onto real lenses. */
public class LensInfo {

    private static final String TAG = "DashcamLens";

    /**
     * 35mm-equivalent focal length of a camera, or 0 if unknown.
     * Uses the ACTIVE ARRAY width (the readout actually used), not the physical sensor size:
     * on this device the tele/ultra-wide sensors are read out with different crops, so the
     * physical size gives wrong zoom ratios (2.13x instead of the real ~3.0x).
     */
    public static float equivFocal(Context ctx, int cameraId) {
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics cc = cm.getCameraCharacteristics(String.valueOf(cameraId));
            float[] focals = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            android.graphics.Rect active = cc.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (focals == null || focals.length == 0 || active == null || active.width() <= 0) return 0;
            return focals[0] / active.width() * 36f;
        } catch (Throwable t) {
            Log.w(TAG, "equivFocal failed for " + cameraId, t);
            return 0;
        }
    }

    /** Optical zoom of a camera relative to the reference (main) camera. */
    public static float zoomRelative(Context ctx, int cameraId, int referenceId) {
        float f = equivFocal(ctx, cameraId);
        float ref = equivFocal(ctx, referenceId);
        if (f <= 0 || ref <= 0) return 1f;
        return f / ref;
    }

    /** Cameras usable for recording (non-front, with a video stream), sorted by focal length. */
    public static int[] recordableCameras(Context ctx) {
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            java.util.List<Integer> ids = new java.util.ArrayList<>();
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
                android.hardware.camera2.params.StreamConfigurationMap map = cc.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null || map.getOutputSizes(android.media.MediaRecorder.class) == null) continue;
                ids.add(Integer.parseInt(id));
            }
            int[] out = new int[ids.size()];
            for (int i = 0; i < out.length; i++) out[i] = ids.get(i);
            java.util.Arrays.sort(out);
            return out;
        } catch (Throwable t) {
            return new int[]{0};
        }
    }
}
