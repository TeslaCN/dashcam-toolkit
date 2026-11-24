package com.dashcam.stitch;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class GroupAdapter extends BaseAdapter {

    private final Context mCtx;
    private final ThumbCache mThumbs;
    private final List<VideoGroup> mData = new ArrayList<>();

    public GroupAdapter(Context ctx, ThumbCache thumbs) {
        mCtx = ctx;
        mThumbs = thumbs;
    }

    public void setData(List<VideoGroup> list) {
        mData.clear();
        mData.addAll(list);
        notifyDataSetChanged();
    }

    public VideoGroup getItem(int pos) {
        return mData.get(pos);
    }

    @Override
    public int getCount() {
        return mData.size();
    }

    @Override
    public long getItemId(int pos) {
        return pos;
    }

    @Override
    public View getView(int pos, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) {
            v = LayoutInflater.from(mCtx).inflate(R.layout.item_group, parent, false);
        }
        VideoGroup g = mData.get(pos);
        TextView title = (TextView) v.findViewById(R.id.txtTitle);
        TextView info = (TextView) v.findViewById(R.id.txtInfo);
        title.setText(g.title);
        int sec = (int) (g.durationMs / 1000);
        info.setText(String.format("%d 视角 · %d:%02d", g.size(), sec / 60, sec % 60));

        List<Uri> uris = g.urisInOrder();
        int[] ids = {R.id.thumb0, R.id.thumb1, R.id.thumb2, R.id.thumb3};
        for (int i = 0; i < ids.length; i++) {
            ImageView iv = (ImageView) v.findViewById(ids[i]);
            if (i < uris.size()) {
                final Uri u = uris.get(i);
                final ImageView fiv = iv;
                fiv.setTag(u.toString());
                fiv.setImageBitmap(mThumbs.peek(u));
                mThumbs.load(u, new ThumbCache.Listener() {
                    @Override
                    public void onLoaded(Uri uri, Bitmap bmp) {
                        if (bmp != null && uri.toString().equals(fiv.getTag())) {
                            fiv.setImageBitmap(bmp);
                        }
                    }
                });
            } else {
                iv.setTag(null);
                iv.setImageBitmap(null);
            }
        }
        return v;
    }
}
