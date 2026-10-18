package com.k4811.common.music;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * Track cover art: first the MediaStore one ({@code content://media/external/audio/media/ID/albumart}),
 * then the image embedded in the file. Does I/O: call off the main thread.
 */
public final class AlbumArt {
    private AlbumArt() {
    }

    /** @return the cover scaled down by half, or null if the track has none. */
    public static Bitmap decode(Context context, long mediaId, String path) {
        if (mediaId > 0) {
            Uri uri = Uri.parse("content://media/external/audio/media/" + mediaId + "/albumart");
            InputStream in = null;
            try {
                in = context.getContentResolver().openInputStream(uri);
                if (in != null) {
                    Bitmap bmp = BitmapFactory.decodeStream(in, null, halfSize());
                    if (bmp != null) return bmp;
                }
            } catch (Exception ignored) {
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        if (path == null || path.isEmpty()) return null;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            byte[] data = r.getEmbeddedPicture();
            if (data != null) return BitmapFactory.decodeStream(new ByteArrayInputStream(data), null, halfSize());
        } catch (Exception ignored) {
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static BitmapFactory.Options halfSize() {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 2;
        return opts;
    }
}
