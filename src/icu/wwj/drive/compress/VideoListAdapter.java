package icu.wwj.drive.compress;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import icu.wwj.drive.R;

/** 视频列表适配器：只有文件名和一行摘要，缩略图在进入压缩页后再生成。 */
public class VideoListAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private final List<VideoEntry> items = new ArrayList<>();

    public VideoListAdapter(Context ctx) {
        this.inflater = LayoutInflater.from(ctx);
    }

    public void setData(List<VideoEntry> data) {
        items.clear();
        if (data != null) items.addAll(data);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public VideoEntry getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).id;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) v = inflater.inflate(R.layout.compress_item, parent, false);
        VideoEntry e = getItem(position);
        TextView title = (TextView) v.findViewById(R.id.txtTitle);
        TextView meta = (TextView) v.findViewById(R.id.txtMeta);
        title.setText(e.displayName);
        meta.setText(e.metaLine());
        return v;
    }
}
