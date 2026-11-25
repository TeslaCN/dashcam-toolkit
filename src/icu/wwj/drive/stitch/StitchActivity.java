package icu.wwj.drive.stitch;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.util.Log;

import icu.wwj.drive.R;

import java.io.FileDescriptor;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class StitchActivity extends Activity {

    private ProgressBar mProgress;
    private TextView mStatus;
    private TextView mTitle;
    private Button mCancel, mPlay, mShare;
    private Composer mComposer;
    private Thread mThread;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private Uri mOutputUri;
    private boolean mFinished;
    private long mStartMs;
    private PowerManager.WakeLock mWake;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.stitch_progress);
        // 同压缩页：不强制屏幕常亮，屏幕可熄灭，合成靠 CPU partial wakelock 继续
        acquireWakeLock();
        mProgress = (ProgressBar) findViewById(R.id.progress);
        mStatus = (TextView) findViewById(R.id.txtStatus);
        mTitle = (TextView) findViewById(R.id.txtTitle);
        mCancel = (Button) findViewById(R.id.btnCancel);
        mPlay = (Button) findViewById(R.id.btnPlay);
        mShare = (Button) findViewById(R.id.btnShare);

        String title = getIntent().getStringExtra("title");
        mTitle.setText(title == null ? "拼接中" : title);

        mCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mFinished) {
                    finish();
                } else {
                    cancelStitch();
                }
            }
        });
        mPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mOutputUri == null) return;
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(mOutputUri, "video/mp4");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(i);
                } catch (Exception ignored) {
                }
            }
        });
        mShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mOutputUri == null) return;
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("video/mp4");
                i.putExtra(Intent.EXTRA_STREAM, mOutputUri);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(Intent.createChooser(i, "分享"));
                } catch (Exception ignored) {
                }
            }
        });

        start();
    }

    private void start() {
        String[] uris = getIntent().getStringArrayExtra("uris");
        if (uris == null || uris.length < 2) {
            finish();
            return;
        }
        Uri[] u = new Uri[uris.length];
        for (int i = 0; i < uris.length; i++) u[i] = Uri.parse(uris[i]);

        Composer.Config cfg = new Composer.Config();
        cfg.uris = u;
        cfg.durationSec = getIntent().getIntExtra("duration", 0);
        cfg.targetBytes = getIntent().getLongExtra("targetBytes", 70_000_000L);
        cfg.boostEnd = getIntent().getBooleanExtra("boost", true);

        mStartMs = System.currentTimeMillis();
        String name = "STITCH_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        cv.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DashcamStitch");
        cv.put(MediaStore.Video.Media.IS_PENDING, 1);
        final Uri outUri;
        final ParcelFileDescriptor pfd;
        try {
            outUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
            if (outUri == null) throw new IllegalStateException("无法创建输出文件");
            pfd = getContentResolver().openFileDescriptor(outUri, "rw");
            if (pfd == null) throw new IllegalStateException("无法写入输出文件");
        } catch (Exception e) {
            mFinished = true;
            mStatus.setText("创建输出失败: " + e.getMessage());
            mTitle.setText("失败");
            mCancel.setText(R.string.stitch_back);
            return;
        }
        mOutputUri = outUri;

        final FileDescriptor fd = pfd.getFileDescriptor();
        mComposer = new Composer(this, cfg, fd, new Composer.Callback() {
            @Override
            public void onProgress(final int percent, final String status) {
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        mProgress.setProgress(percent);
                        long sec = (System.currentTimeMillis() - mStartMs) / 1000;
                        mStatus.setText(percent + "% · 已用 " + sec + "s");
                    }
                });
            }

            @Override
            public void onLog(final String msg) {
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        mTitle.setText(msg);
                    }
                });
            }

            @Override
            public void onResult(final boolean ok, final String message) {
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            pfd.close();
                        } catch (Exception ignored) {
                        }
                        mFinished = true;
                        mCancel.setEnabled(true);   // 取消后按钮曾被禁用，这里恢复
                        Log.i("StitchTest", "RESULT ok=" + ok + " uri=" + mOutputUri
                                + " msg=" + message);
                        if (ok) {
                            ContentValues v = new ContentValues();
                            v.put(MediaStore.Video.Media.IS_PENDING, 0);
                            try {
                                getContentResolver().update(mOutputUri, v, null, null);
                            } catch (Exception ignored) {
                            }
                            mProgress.setProgress(100);
                            long sec = (System.currentTimeMillis() - mStartMs) / 1000;
                            String sizeStr = "";
                            try {
                                long sz = 0;
                                android.database.Cursor c = getContentResolver().query(mOutputUri,
                                        new String[]{android.provider.MediaStore.Video.Media.SIZE},
                                        null, null, null);
                                if (c != null) {
                                    if (c.moveToFirst()) sz = c.getLong(0);
                                    c.close();
                                }
                                if (sz > 0) sizeStr = String.format(java.util.Locale.US, " · %.1f MB", sz / 1000000.0);
                            } catch (Exception ignored) {
                            }
                            mTitle.setText(R.string.stitch_done);
                            mStatus.setText("用时 " + sec + " 秒" + sizeStr + " · " + message);
                            mCancel.setText(R.string.stitch_back);
                            mPlay.setVisibility(View.VISIBLE);
                            mShare.setVisibility(View.VISIBLE);
                        } else {
                            try {
                                getContentResolver().delete(mOutputUri, null, null);
                            } catch (Exception ignored) {
                            }
                            mTitle.setText("失败");
                            mStatus.setText(message);
                            mCancel.setText(R.string.stitch_back);
                        }
                    }
                });
            }
        });

        mThread = new Thread(new Runnable() {
            @Override
            public void run() {
                mComposer.run();
            }
        }, "stitch");
        mThread.start();
    }

    private void cancelStitch() {
        if (mComposer != null) mComposer.cancel();
        mStatus.setText("正在取消…");
        mCancel.setEnabled(false);
        // 兜底：万一 onResult 没回来，1.5 秒后也允许返回
        mMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!mFinished) mCancel.setEnabled(true);
            }
        }, 1500);
    }

    @Override
    public void onBackPressed() {
        if (!mFinished) {
            cancelStitch();
            return;
        }
        super.onBackPressed();
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            mWake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "icu.wwj.drive:stitch");
            mWake.setReferenceCounted(false);
            mWake.acquire(2 * 60 * 60 * 1000L);
        } catch (Exception ignored) {
        }
    }

    private void releaseWakeLock() {
        try {
            if (mWake != null && mWake.isHeld()) mWake.release();
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseWakeLock();
        if (mComposer != null && !mFinished) mComposer.cancel();
    }
}
