package icu.wwj.drive.stitch;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.Size;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 缩略图异步加载 + 内存缓存 */
public class ThumbCache {
    public interface Listener {
        void onLoaded(Uri uri, Bitmap bmp);
    }

    private final ContentResolver mCr;
    private final LruCache<String, Bitmap> mCache;
    private final ExecutorService mPool = Executors.newFixedThreadPool(2);
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final java.util.Map<String, java.util.List<Listener>> mWaiters = new java.util.HashMap<>();

    public ThumbCache(ContentResolver cr) {
        mCr = cr;
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        mCache = new LruCache<String, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    public Bitmap peek(Uri uri) {
        return mCache.get(uri.toString());
    }

    public void load(final Uri uri, final Listener l) {
        final String k = uri.toString();
        Bitmap b = mCache.get(k);
        if (b != null) {
            l.onLoaded(uri, b);
            return;
        }
        synchronized (mWaiters) {
            List<Listener> list = mWaiters.get(k);
            if (list != null) {
                list.add(l);            // 正在加载：登记，加载完一起回调
                return;
            }
            list = new java.util.ArrayList<>();
            list.add(l);
            mWaiters.put(k, list);
        }
        mPool.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = null;
                try {
                    bmp = mCr.loadThumbnail(uri, new Size(320, 320), null);
                } catch (Exception ignored) {
                }
                if (bmp != null) mCache.put(k, bmp);
                final Bitmap fb = bmp;
                final List<Listener> listeners;
                synchronized (mWaiters) {
                    listeners = mWaiters.remove(k);
                }
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        if (listeners != null) {
                            for (Listener x : listeners) x.onLoaded(uri, fb);
                        }
                    }
                });
            }
        });
    }

    public void shutdown() {
        mPool.shutdownNow();
    }
}
