package icu.wwj.drive.dashcam;

/** Snapshot of recorder state shared between RecordingSession, RecordService and the UI. */
public class Status {
    public boolean recording;
    public long startWallMs;
    public long elapsedMs;
    public int segments;
    public long sessionBytes;
    public long freeBytes;
    public long quotaBytes;
    public boolean gpsFix;
    public double lat;
    public double lon;
    public float speedKph;
    public float batteryTempC = -1;
    public int thermalStatus;
    public String file = "";
    public String resolution = "";
    public String lastError = "";
    public int iso = -1;
    public float luma = -1;
    public String exposureLabel = "";
    public String placeName = "";
    public int reconnects;

    public Status copy() {
        Status s = new Status();
        s.recording = recording;
        s.startWallMs = startWallMs;
        s.elapsedMs = elapsedMs;
        s.segments = segments;
        s.sessionBytes = sessionBytes;
        s.freeBytes = freeBytes;
        s.quotaBytes = quotaBytes;
        s.gpsFix = gpsFix;
        s.lat = lat;
        s.lon = lon;
        s.speedKph = speedKph;
        s.batteryTempC = batteryTempC;
        s.thermalStatus = thermalStatus;
        s.file = file;
        s.resolution = resolution;
        s.lastError = lastError;
        s.iso = iso;
        s.luma = luma;
        s.exposureLabel = exposureLabel;
        s.placeName = placeName;
        s.reconnects = reconnects;
        return s;
    }
}
