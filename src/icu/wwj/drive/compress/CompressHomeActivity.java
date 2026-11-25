package icu.wwj.drive.compress;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import icu.wwj.drive.R;

/**
 * 视频压缩入口：列出相册里的视频，选一条 → 选目标体积 → 进入压缩页。
 *
 * <p>自动化入口（adb）：
 * {@code am start -n icu.wwj.drive/.compress.CompressHomeActivity
 * --ez autostart true --es name 关键字 --ei targetMB 30}
 */
public class CompressHomeActivity extends Activity {

    private static final String TAG = "CompressTest";
    private static final int REQ_PERM = 2001;

    private ListView mList;
    private TextView mStatus;
    private Button mRefresh;
    private VideoListAdapter mAdapter;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private boolean mLoading;
    private boolean mWaitPermission;

    private boolean mAutoStart;
    private String mAutoName;
    private int mAutoTargetMB;
    private int mAutoRatioPct;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.compress_main);

        mList = (ListView) findViewById(R.id.list);
        mStatus = (TextView) findViewById(R.id.txtStatus);
        mRefresh = (Button) findViewById(R.id.btnRefresh);
        mAdapter = new VideoListAdapter(this);
        mList.setAdapter(mAdapter);

        mRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load();
            }
        });
        mList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                showOptions(mAdapter.getItem(position));
            }
        });

        readAutoExtras(getIntent());
        if (!hasPermission()) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM);
        } else {
            load();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mWaitPermission && hasPermission()) {
            mWaitPermission = false;
            load();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readAutoExtras(intent);
        if (mAutoStart) load();
    }

    private void readAutoExtras(Intent intent) {
        if (intent == null) return;
        mAutoStart = intent.getBooleanExtra("autostart", false);
        mAutoName = intent.getStringExtra("name");
        mAutoTargetMB = intent.getIntExtra("targetMB", 0);
        mAutoRatioPct = intent.getIntExtra("ratioPct", 0);
        if (mAutoStart) {
            Log.i(TAG, "AUTOSTART name=" + mAutoName + " targetMB=" + mAutoTargetMB
                    + " ratioPct=" + mAutoRatioPct);
        }
    }

    private boolean hasPermission() {
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == REQ_PERM) {
            if (hasPermission()) {
                load();
            } else {
                mWaitPermission = true;
                mStatus.setVisibility(View.VISIBLE);
                mStatus.setText("没有相册读取权限，扫不到视频。\n请允许「视频/存储」权限后点刷新，或点下面的「去设置」。");
                new AlertDialog.Builder(this)
                        .setTitle("需要相册权限")
                        .setMessage("压缩素材来自系统相册里的视频。\n请在系统设置里允许本应用读取视频/存储。")
                        .setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                try {
                                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:" + getPackageName())));
                                } catch (Exception ignored) {
                                }
                            }
                        })
                        .setNegativeButton("重试", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (hasPermission()) load();
                                else requestPermissions(new String[]{
                                        Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM);
                            }
                        })
                        .show();
            }
        }
    }

    private void load() {
        if (mLoading || !hasPermission()) return;
        mLoading = true;
        mStatus.setVisibility(View.VISIBLE);
        mStatus.setText(R.string.compress_loading);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<VideoEntry> list = queryVideos();
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        mLoading = false;
                        mAdapter.setData(list);
                        Log.i(TAG, "VIDEOS n=" + list.size());
                        if (list.isEmpty()) {
                            mStatus.setVisibility(View.VISIBLE);
                            mStatus.setText(R.string.compress_empty);
                        } else {
                            mStatus.setVisibility(View.GONE);
                        }
                        if (mAutoStart) {
                            mAutoStart = false;
                            VideoEntry pick = pickAuto(list);
                            if (pick == null) {
                                Log.i(TAG, "AUTOSTART no-match");
                                mStatus.setVisibility(View.VISIBLE);
                                mStatus.setText("autostart: 没找到名字匹配的视频");
                            } else {
                                Log.i(TAG, "AUTOSTART pick=" + pick.displayName);
                                start(pick, mAutoTargetMB * 1_000_000L,
                                        mAutoRatioPct > 0 ? mAutoRatioPct : 50);
                            }
                        }
                    }
                });
            }
        }, "compress-list").start();
    }

    private VideoEntry pickAuto(List<VideoEntry> list) {
        if (mAutoName != null && !mAutoName.isEmpty()) {
            String q = mAutoName.toLowerCase(java.util.Locale.US);
            for (VideoEntry e : list) {
                if (e.displayName != null
                        && e.displayName.toLowerCase(java.util.Locale.US).contains(q)) {
                    return e;
                }
            }
            return null;
        }
        return list.isEmpty() ? null : list.get(0);
    }

    private List<VideoEntry> queryVideos() {
        List<VideoEntry> out = new ArrayList<>();
        String[] proj = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.MIME_TYPE,
        };
        Cursor c = null;
        try {
            c = getContentResolver().query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj,
                    MediaStore.Video.Media.DURATION + ">0", null,
                    MediaStore.Video.Media.DATE_ADDED + " DESC");
            if (c == null) return out;
            int idI = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int nameI = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int durI = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);
            int sizeI = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
            int wI = c.getColumnIndex(MediaStore.Video.Media.WIDTH);
            int hI = c.getColumnIndex(MediaStore.Video.Media.HEIGHT);
            int mimeI = c.getColumnIndex(MediaStore.Video.Media.MIME_TYPE);
            while (c.moveToNext()) {
                long id = c.getLong(idI);
                String name = c.getString(nameI);
                // 只列源视频：本功能自己的产物（_compressed / 时间戳后缀）不再当作素材
                if (name == null || name.contains("_compressed")) continue;
                out.add(new VideoEntry(
                        id,
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI.buildUpon()
                                .appendPath(String.valueOf(id)).build(),
                        name,
                        c.getLong(durI),
                        c.getLong(sizeI),
                        wI >= 0 ? c.getInt(wI) : 0,
                        hI >= 0 ? c.getInt(hI) : 0,
                        mimeI >= 0 ? c.getString(mimeI) : null));
            }
        } catch (Exception e) {
            Log.w(TAG, "query failed", e);
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    private void showOptions(final VideoEntry entry) {
        View v = LayoutInflater.from(this).inflate(R.layout.compress_dialog, null);
        final RadioGroup rg = (RadioGroup) v.findViewById(R.id.rgMode);
        final EditText etMB = (EditText) v.findViewById(R.id.etMB);
        final TextView estimate = (TextView) v.findViewById(R.id.txtEstimate);

        final Runnable update = new Runnable() {
            @Override
            public void run() {
                int checked = rg.getCheckedRadioButtonId();
                long target;
                if (checked == R.id.rbCustom) {
                    target = targetBytesFor(checked, etMB, entry);
                    if (target <= 0) {
                        estimate.setText("请输入自定义大小（MB）");
                        return;
                    }
                } else {
                    int pct = checked == R.id.rb30 ? 30 : 50;
                    target = Math.max(1, entry.sizeBytes * pct / 100);
                }
                double pct = entry.sizeBytes > 0 ? target * 100.0 / entry.sizeBytes : 0;
                estimate.setText("目标 " + MediaInfo.formatSize(target)
                        + (pct > 0 ? String.format(java.util.Locale.US, "（原片的 %.0f%%）", pct) : "")
                        + "\n分辨率、帧率、编码、音轨保持不变，只降码率。");
            }
        };
        rg.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                update.run();
            }
        });
        etMB.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                update.run();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        update.run();

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(entry.displayName + "\n" + entry.metaLine())
                .setView(v)
                .setPositiveButton(R.string.compress_start, null)
                .setNegativeButton(R.string.compress_cancel, null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View btn) {
                                int checked = rg.getCheckedRadioButtonId();
                                long target = targetBytesFor(checked, etMB, entry);
                                if (checked == R.id.rbCustom && target <= 0) {
                                    etMB.setError("请输入目标大小（MB）");
                                    return;
                                }
                                int ratio = checked == R.id.rb30 ? 30 : 50;
                                dialog.dismiss();
                                start(entry, target, ratio);
                            }
                        });
            }
        });
        dialog.show();
    }

    private long targetBytesFor(int checkedId, EditText etMB, VideoEntry entry) {
        if (checkedId == R.id.rbCustom) {
            String s = etMB.getText().toString().trim();
            if (s.isEmpty()) return 0;
            try {
                long mb = Long.parseLong(s);
                return mb > 0 ? mb * 1_000_000L : 0;
            } catch (Exception e) {
                return 0;
            }
        }
        // 比例模式由 CompressActivity/引擎按原文件大小折算，这里给 0
        return 0;
    }

    private void start(VideoEntry entry, long targetBytes, int ratioPct) {
        Intent it = new Intent(this, CompressActivity.class);
        it.putExtra("input", entry.uri.toString());
        it.putExtra("name", entry.displayName);
        it.putExtra("targetBytes", targetBytes);
        it.putExtra("ratioPct", ratioPct);
        startActivity(it);
    }
}
