package icu.wwj.drive.stitch;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.util.Log;

import icu.wwj.drive.R;

import java.util.List;

public class StitchHomeActivity extends Activity {

    private static final int REQ_PERM = 1001;

    private ListView mList;
    private TextView mStatus;
    private Button mRefresh;
    private GroupAdapter mAdapter;
    private ThumbCache mThumbs;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private boolean mLoading;
    // 自动化测试入口：am start ... --ez autostart true --ei duration 30 --ei size 45
    private boolean mAutoStart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.stitch_main);
        mList = (ListView) findViewById(R.id.list);
        mStatus = (TextView) findViewById(R.id.txtStatus);
        mRefresh = (Button) findViewById(R.id.btnRefresh);
        mThumbs = new ThumbCache(getContentResolver());
        mAdapter = new GroupAdapter(this, mThumbs);
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

        cleanupStalePending();
        cleanupOrphans();
        mAutoStart = getIntent().getBooleanExtra("autostart", false);
        if (!hasPermission()) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
        } else {
            load();
        }
    }

    /** 清理进程被杀留下的孤儿文件：文件在、MediaStore 里没有记录（或还挂着 IS_PENDING） */
    private void cleanupOrphans() {
        try {
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_MOVIES), "DashcamStitch");
            java.io.File[] files = dir.listFiles();
            Log.i("StitchTest", "ORPHANSCAN dir=" + dir + " exists=" + dir.exists()
                    + " files=" + (files == null ? -1 : files.length));
            if (files == null) return;
            java.util.Set<String> known = new java.util.HashSet<>();
            android.database.Cursor c = getContentResolver().query(
                    android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    new String[]{android.provider.MediaStore.Video.Media.DISPLAY_NAME,
                            android.provider.MediaStore.Video.Media.IS_PENDING},
                    android.provider.MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?",
                    new String[]{"Movies/DashcamStitch%"}, null);
            if (c != null) {
                int ni = c.getColumnIndex(android.provider.MediaStore.Video.Media.DISPLAY_NAME);
                int pi = c.getColumnIndex(android.provider.MediaStore.Video.Media.IS_PENDING);
                while (c.moveToNext()) {
                    String n = ni >= 0 ? c.getString(ni) : null;
                    int pending = pi >= 0 ? c.getInt(pi) : 0;
                    if (n != null && pending == 0) known.add(n);   // 正式记录才算“有主”
                }
                c.close();
            }
            long now = System.currentTimeMillis();
            for (java.io.File f : files) {
                if (!f.getName().startsWith("STITCH_") || !f.getName().endsWith(".mp4")) continue;
                if (now - f.lastModified() < 60000) continue;      // 刚写的别动
                if (!known.contains(f.getName())) {
                    boolean ok = f.delete();
                    Log.i("StitchTest", "ORPHAN " + f.getName() + " known=" + known.size()
                            + " deleted=" + ok + " size=" + f.length());
                }
            }
        } catch (Exception e) {
            Log.i("StitchTest", "ORPHANSCAN error " + e);
        }
    }

    /** 清理上次进程被杀残留的 IS_PENDING 行，避免占空间且相册看不到 */
    private void cleanupStalePending() {
        try {
            int n = getContentResolver().delete(
                    android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    android.provider.MediaStore.Video.Media.RELATIVE_PATH + " LIKE ? AND "
                            + android.provider.MediaStore.Video.Media.IS_PENDING + "=1",
                    new String[]{"Movies/DashcamStitch%"});
            if (n > 0) Log.i("StitchTest", "CLEANUP pending=" + n);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // 便于脚本连续触发：singleTop 复用实例时重新处理 autostart
        mAutoStart = intent.getBooleanExtra("autostart", false);
        if (mAutoStart) load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mThumbs != null) mThumbs.shutdown();
    }

    private boolean hasPermission() {
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == REQ_PERM) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
                load();
            } else {
                mStatus.setText("需要读取相册权限才能找到素材");
            }
        }
    }

    private void load() {
        if (mLoading) return;
        mLoading = true;
        mStatus.setVisibility(View.VISIBLE);
        mStatus.setText(R.string.stitch_loading);
        mAdapter.setData(new java.util.ArrayList<VideoGroup>());
        new Thread(new Runnable() {
            @Override
            public void run() {
                final MediaScanner.Result r = MediaScanner.scan(getContentResolver());
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        mLoading = false;
                        List<VideoGroup> gs = r.groups;
                        mAdapter.setData(gs);
                        Log.i("StitchTest", "GROUPS n=" + gs.size() + " scanned=" + r.scanned);
                        if (gs.isEmpty()) {
                            mStatus.setText("没有找到多视角素材\n\n需要同一时间戳的 MAIN / LEFT / BACK / RIGHT 四个文件。\n已扫描 " + r.scanned + " 个视频。");
                        } else {
                            mStatus.setVisibility(View.GONE);
                        }
                        if (mAutoStart && !gs.isEmpty()) {
                            mAutoStart = false;
                            VideoGroup g0 = gs.get(0);
                            int dur = getIntent().getIntExtra("duration", 0);
                            int sizeMb = getIntent().getIntExtra("sizeMB", 70);
                            long size = Math.max(1, sizeMb) * 1000000L;
                            boolean boost = getIntent().getBooleanExtra("boost", true);
                            Log.i("StitchTest", "AUTOSTART " + g0.title + " views=" + g0.size()
                                    + " dur=" + dur + " size=" + size);
                            startStitch(g0, dur, size, boost);
                        }
                    }
                });
            }
        }).start();
    }

    private void showOptions(final VideoGroup g) {
        View v = LayoutInflater.from(this).inflate(R.layout.stitch_dialog, null);
        final RadioGroup rg = (RadioGroup) v.findViewById(R.id.rgSize);
        final EditText et = (EditText) v.findViewById(R.id.etDuration);
        final EditText etSize = (EditText) v.findViewById(R.id.etSize);
        final CheckBox cb = (CheckBox) v.findViewById(R.id.cbBoost);
        int fullSec = (int) (g.durationMs / 1000);

        new AlertDialog.Builder(this)
                .setTitle(g.title + " · " + g.size() + " 视角")
                .setView(v)
                .setPositiveButton(R.string.stitch_start, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        long targetBytes;
                        String sz = etSize.getText().toString().trim();
                        long custom = 0;
                        if (!sz.isEmpty()) {
                            try { custom = Long.parseLong(sz) * 1000000L; } catch (Exception ignored) { }
                        }
                        if (custom > 0) {
                            targetBytes = custom;
                        } else {
                            int checked = rg.getCheckedRadioButtonId();
                            if (checked == R.id.rb40) targetBytes = 40L * 1000000L;
                            else if (checked == R.id.rb140) targetBytes = 140L * 1000000L;
                            else targetBytes = 70L * 1000000L;
                        }
                        int dur = 0;
                        String t = et.getText().toString().trim();
                        if (!t.isEmpty()) {
                            try {
                                dur = Integer.parseInt(t);
                            } catch (Exception ignored) {
                            }
                        }
                        startStitch(g, dur, targetBytes, cb.isChecked());
                    }
                })
                .setNegativeButton(R.string.stitch_cancel, null)
                .show();
    }

    private void startStitch(VideoGroup g, int dur, long targetBytes, boolean boost) {
        Uri[] uris = g.orderedUris();
        String[] arr = new String[uris.length];
        for (int i = 0; i < uris.length; i++) arr[i] = uris[i].toString();
        Intent it = new Intent(this, StitchActivity.class);
        it.putExtra("uris", arr);
        it.putExtra("duration", dur);
        it.putExtra("targetBytes", targetBytes);
        it.putExtra("boost", boost);
        it.putExtra("title", g.title);
        startActivity(it);
    }
}
