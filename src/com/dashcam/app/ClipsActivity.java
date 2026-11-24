package com.dashcam.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Gallery-style grid of recorded clips: thumbnail + start time, tap to play, long-press for actions. */
public class ClipsActivity extends Activity {

    private GridView grid;
    private TextView header;
    private final List<File> files = new ArrayList<>();
    private final Map<String, Bitmap> thumbs = new HashMap<>();
    private final ExecutorService thumbExecutor = Executors.newFixedThreadPool(2);
    private ClipsAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0B0F14"));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(10), dp(16), dp(6));

        header = new TextView(this);
        header.setTextColor(Color.parseColor("#8B949E"));
        header.setTextSize(13);
        header.setText("录像文件");
        top.addView(header, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button back = new Button(this);
        back.setText("返回");
        back.setAllCaps(false);
        back.setTextColor(Color.WHITE);
        back.setBackgroundColor(Color.parseColor("#21262D"));
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(96), dp(40)));
        root.addView(top);

        grid = new GridView(this);
        grid.setNumColumns(getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ? 4 : 2);
        grid.setHorizontalSpacing(dp(6));
        grid.setVerticalSpacing(dp(10));
        grid.setPadding(dp(8), dp(4), dp(8), dp(8));
        grid.setBackgroundColor(Color.parseColor("#0B0F14"));
        adapter = new ClipsAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> play(files.get(position)));
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            showActions(files.get(position));
            return true;
        });
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        getWindow().setStatusBarColor(Color.parseColor("#0B0F14"));
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        thumbExecutor.shutdownNow();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void reload() {
        files.clear();
        files.addAll(StorageManager.listClips(StorageManager.recordingsDir(this)));
        if (header != null) {
            long bytes = 0;
            for (File f : files) bytes += f.length();
            header.setText(files.size() + " 个文件 · " + NotificationHelper.formatSize(bytes)
                    + " · 点击播放，长按更多");
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private String startTime(File f) {
        String n = f.getName();
        try {
            if (n.startsWith("DASHCAM_") || n.startsWith("EVENT_")) {
                String body = n.substring(n.indexOf('_') + 1);
                String date = body.substring(0, 8);
                String time = body.substring(9, 15);
                return date.substring(0, 4) + "-" + date.substring(4, 6) + "-" + date.substring(6, 8)
                        + " " + time.substring(0, 2) + ":" + time.substring(2, 4) + ":" + time.substring(4, 6);
            }
        } catch (Throwable ignored) {
        }
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date(f.lastModified()));
    }

    private Bitmap thumbnail(File f) {
        Bitmap cached = thumbs.get(f.getName());
        if (cached != null) return cached;
        File cacheFile = new File(new File(getCacheDir(), "thumbs"), f.getName() + ".jpg");
        try {
            if (cacheFile.exists()) {
                Bitmap b = android.graphics.BitmapFactory.decodeFile(cacheFile.getAbsolutePath());
                if (b != null) {
                    thumbs.put(f.getName(), b);
                    return b;
                }
            }
        } catch (Throwable ignored) {
        }
        Bitmap bmp = null;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            long dur = 0;
            try {
                dur = Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            } catch (Throwable ignored) {
            }
            long us = Math.max(0, dur / 3) * 1000L;
            try {
                bmp = r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180);
            } catch (Throwable ignored) {
            }
            if (bmp == null) {
                bmp = r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
        if (bmp == null) return null;
        thumbs.put(f.getName(), bmp);
        try {
            File dir = cacheFile.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            FileOutputStream out = new FileOutputStream(cacheFile);
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, out);
            out.close();
        } catch (Throwable ignored) {
        }
        return bmp;
    }

    private void play(File f) {
        Uri uri = ClipProvider.uriFor(f.getName());
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "video/mp4");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "没有可播放的应用：" + t, Toast.LENGTH_LONG).show();
        }
    }

    private void showActions(File f) {
        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setItems(new String[]{"播放", "删除", "分享"}, (d, which) -> {
                    if (which == 0) {
                        play(f);
                    } else if (which == 1) {
                        new AlertDialog.Builder(this)
                                .setMessage("删除 " + f.getName() + "？")
                                .setPositiveButton("删除", (dd, ww) -> {
                                    if (StorageManager.delete(ClipsActivity.this, f)) {
                                        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                                        reload();
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    } else {
                        Uri uri = ClipProvider.uriFor(f.getName());
                        Intent share = new Intent(Intent.ACTION_SEND);
                        share.setType("video/mp4");
                        share.putExtra(Intent.EXTRA_STREAM, uri);
                        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        try {
                            startActivity(Intent.createChooser(share, "分享录像"));
                        } catch (Throwable t) {
                            Toast.makeText(this, "分享失败：" + t, Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    private class ClipsAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return files.size();
        }

        @Override
        public Object getItem(int position) {
            return files.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout item;
            if (convertView == null) {
                item = new LinearLayout(ClipsActivity.this);
                item.setOrientation(LinearLayout.VERTICAL);
                ImageView iv = new ImageView(ClipsActivity.this);
                iv.setId(1001);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundColor(Color.parseColor("#161B22"));
                int w = parent.getWidth() > 0 ? (parent.getWidth() - dp(40)) / grid.getNumColumns()
                        : dp(200);
                int h = Math.max(dp(60), w * 9 / 16);
                item.addView(iv, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, h));
                TextView t1 = new TextView(ClipsActivity.this);
                t1.setId(1002);
                t1.setTextColor(Color.parseColor("#E6EDF3"));
                t1.setTextSize(12);
                t1.setPadding(dp(2), dp(4), dp(2), 0);
                item.addView(t1);
                TextView t2 = new TextView(ClipsActivity.this);
                t2.setId(1003);
                t2.setTextColor(Color.parseColor("#8B949E"));
                t2.setTextSize(11);
                t2.setPadding(dp(2), 0, dp(2), dp(2));
                item.addView(t2);
            } else {
                item = (LinearLayout) convertView;
            }
            File f = files.get(position);
            ImageView iv = item.findViewById(1001);
            TextView t1 = item.findViewById(1002);
            TextView t2 = item.findViewById(1003);
            String label = (f.getParentFile() != null && "events".equals(f.getParentFile().getName()))
                    ? "★ 事件 " + startTime(f) : startTime(f);
            t1.setText(label);
            t2.setText(NotificationHelper.formatSize(f.length()));
            iv.setImageBitmap(null);
            final String key = f.getName();
            final ImageView target = iv;
            target.setTag(key);
            Bitmap bmp = thumbs.get(key);
            if (bmp != null) {
                target.setImageBitmap(bmp);
            } else {
                thumbExecutor.execute(() -> {
                    final Bitmap b = thumbnail(f);
                    if (b != null) {
                        runOnUiThread(() -> {
                            if (key.equals(target.getTag())) target.setImageBitmap(b);
                        });
                    }
                });
            }
            return item;
        }
    }

}
