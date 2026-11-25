package icu.wwj.drive.compress;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.concurrent.atomic.AtomicBoolean;

import icu.wwj.drive.R;

/**
 * 压缩进度页：分析 → 转码（进度/帧数）→ 结果（体积、画质参数）→ 播放/分享。
 * 中途可取消；取消或失败都会删掉写到相册的 pending 文件。
 */
public class CompressActivity extends Activity {

    private static final String TAG = "CompressTest";

    private TextView mTitle;
    private TextView mStatus;
    private ProgressBar mProgress;
    private Button mBtnCancel;
    private Button mPlay;
    private Button mShare;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock mWake;
    private final AtomicBoolean mCancel = new AtomicBoolean(false);
    private volatile boolean mFinished;
    private volatile CompressResult mResult;
    private long mStartMs;
    private long mSourceBytes;
    private long mTargetBytes;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.compress_progress);
        // 不强制屏幕常亮（OLED 烧屏风险）：屏幕可以自动熄灭/锁屏，
        // 转码靠 CPU 的 partial wakelock 继续跑，完成后再释放。
        acquireWakeLock();

        mTitle = (TextView) findViewById(R.id.txtTitle);
        mStatus = (TextView) findViewById(R.id.txtStatus);
        mProgress = (ProgressBar) findViewById(R.id.progress);
        mBtnCancel = (Button) findViewById(R.id.btnCancel);
        mPlay = (Button) findViewById(R.id.btnPlay);
        mShare = (Button) findViewById(R.id.btnShare);
        mPlay.setVisibility(View.GONE);
        mShare.setVisibility(View.GONE);
        mTitle.setText(R.string.compress_compress_title);
        mStatus.setText(R.string.compress_prepare);

        mBtnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelAndFinish();
            }
        });
        mPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openResult(false);
            }
        });
        mShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openResult(true);
            }
        });

        String input = getIntent().getStringExtra("input");
        mTargetBytes = getIntent().getLongExtra("targetBytes", 0);
        int ratioPct = getIntent().getIntExtra("ratioPct", 50);
        if (input == null) {
            fail("缺少输入视频");
            return;
        }
        final Uri uri = Uri.parse(input);
        mStartMs = System.currentTimeMillis();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final MediaInfo info = MediaInfo.probe(CompressActivity.this, uri);
                    mSourceBytes = info.sizeBytes;
                    if (info.tenBitOrHdr) {
                        throw new Exception("源视频是 10bit/HDR（" + info.codecLabel()
                                + "），本机硬件编码器不支持保持画质压缩，已放弃。");
                    }
                    final CompressOptions opt = new CompressOptions(info, mTargetBytes, ratioPct);
                    Log.i(TAG, "INPUT " + info.displayName + " " + info.resolutionLabel()
                            + " " + info.codecLabel() + " fps=" + info.fpsLabel()
                            + " bitrate=" + MediaInfo.formatBitrate(info.videoBitrate)
                            + " target=" + opt.effectiveTargetBytes());
                    CompressResult r = Compressor.compress(CompressActivity.this, opt,
                            new Compressor.Callback() {
                                @Override
                                public void onProgress(final int percent, final String status) {
                                    mMain.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (mFinished) return;
                                            int p = Math.max(0, Math.min(100, percent));
                                            mProgress.setProgress(p);
                                            mStatus.setText(p + "% · " + status);
                                        }
                                    });
                                }
                            },
                            mCancel);
                    if (mCancel.get()) throw new Compressor.CancelledException();
                    mResult = r;
                    mFinished = true;
                    mMain.post(new Runnable() {
                        @Override
                        public void run() {
                            showDone(r);
                        }
                    });
                } catch (Compressor.CancelledException ce) {
                    mFinished = true;
                    mMain.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!isFinishing()) finish();
                        }
                    });
                } catch (final Exception e) {
                    mFinished = true;
                    Log.w(TAG, "FAIL", e);
                    mMain.post(new Runnable() {
                        @Override
                        public void run() {
                            fail(e.getMessage() == null ? e.toString() : e.getMessage());
                        }
                    });
                }
            }
        }, "compress-worker").start();
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            mWake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "icu.wwj.drive:compress");
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

    private void cancelAndFinish() {
        mCancel.set(true);
        mStatus.setText("正在取消…");
        mBtnCancel.setEnabled(false);
        if (!mFinished && mResult == null) {
            // 引擎会抛 CancelledException 后自己清理；这里等它结束
        } else {
            finish();
        }
    }

    private void showDone(final CompressResult r) {
        releaseWakeLock();
        mProgress.setProgress(100);
        mTitle.setText(R.string.compress_done);
        long sec = Math.max(1, (System.currentTimeMillis() - mStartMs) / 1000);
        double pct = mSourceBytes > 0 ? r.bytes * 100.0 / mSourceBytes : 0;
        StringBuilder sb = new StringBuilder();
        sb.append(MediaInfo.formatSize(r.bytes));
        if (pct > 0) sb.append(String.format(java.util.Locale.US, "（原片的 %.0f%%）", pct));
        sb.append(" · ").append(r.width).append('×').append(r.height);
        sb.append(" · ").append(r.codecLabel());
        sb.append(" · ").append(r.encodedFrames).append(" 帧");
        sb.append(" · 用时 ").append(sec).append(" 秒");
        if (r.retries > 0) sb.append("\n为提高命中率重试了 ").append(r.retries).append(" 次");
        sb.append("\n已保存到相册 Movies/Compressed/");
        mStatus.setText(sb.toString());
        mBtnCancel.setText(R.string.compress_back);
        mBtnCancel.setEnabled(true);
        mBtnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        mPlay.setVisibility(View.VISIBLE);
        mShare.setVisibility(View.VISIBLE);
        Log.i(TAG, "RESULT ok=true bytes=" + r.bytes + " frames=" + r.encodedFrames
                + " " + r.width + "x" + r.height + " mime=" + r.videoMime
                + " audio=" + r.audioCopied + " retries=" + r.retries);
    }

    private void fail(String msg) {
        releaseWakeLock();
        mProgress.setVisibility(View.GONE);
        mTitle.setText("压缩失败");
        mStatus.setText(msg);
        mBtnCancel.setText(R.string.compress_back);
        mBtnCancel.setEnabled(true);
        mBtnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        Log.i(TAG, "RESULT ok=false msg=" + msg);
    }

    private void openResult(boolean share) {
        CompressResult r = mResult;
        if (r == null) return;
        try {
            if (share) {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("video/mp4");
                i.putExtra(Intent.EXTRA_STREAM, r.outputUri);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "分享压缩视频"));
            } else {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(r.outputUri, "video/mp4");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(i);
            }
        } catch (Exception e) {
            mStatus.setText("无法打开：没有可用的播放器/分享目标");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseWakeLock();
        if (!mFinished) {
            mCancel.set(true);
        }
    }
}
