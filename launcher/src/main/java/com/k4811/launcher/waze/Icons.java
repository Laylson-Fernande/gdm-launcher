package com.k4811.launcher.waze;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PorterDuff;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.io.InputStream;

/** CarIcon / IconCompat (Bundle do IconCompat.toBundle) -> Drawable. Roda no processo do Waze. */
final class Icons {
    private Icons() {
    }

    /** CarIcon.mType: 1 custom, 2 voltar, 3 alerta, 4 ícone do app, 5 erro, 6 mover, 7 mensagem. */
    static final int TYPE_CUSTOM = 1;
    static final int TYPE_BACK = 2;
    static final int TYPE_APP_ICON = 4;
    static final int TYPE_PAN = 6;

    @SuppressWarnings("deprecation")
    static Drawable drawable(Context ctx, CarBundle.Obj carIcon, boolean dark) {
        if (carIcon == null) return null;
        Object icon = carIcon.get("mIcon");
        Drawable d = null;
        int type = carIcon.i("mType", TYPE_CUSTOM);
        if (icon instanceof CarBundle.Image) {
            d = fromIconCompat(ctx, ((CarBundle.Image) icon).bundle);
        } else if (type == TYPE_APP_ICON) {
            try {
                d = ctx.getPackageManager().getApplicationIcon(WazeUi.WAZE);
            } catch (Exception ignored) {
            }
        } else if (type == TYPE_BACK) {
            d = WazeUi.icon(ctx, "back", WazeUi.dp(40), WazeUi.text());
        }
        if (d == null) return null;
        CarBundle.Obj tint = carIcon.obj("mTint");
        if (tint != null) {
            d = d.mutate();
            d.setColorFilter(Colors.icon(tint, dark, WazeUi.text()), PorterDuff.Mode.SRC_IN);
        }
        return d;
    }

    /** Nome do recurso do ícone (IconCompat de recurso), para reconhecer ações como a busca. */
    static String resourceName(Context ctx, CarBundle.Obj carIcon) {
        Bundle b = iconBundle(carIcon);
        if (b == null || b.getInt("type", -1) != 2) return null;
        try {
            String pkg = b.getString("string1");
            Resources res = pkg == null || pkg.equals(ctx.getPackageName()) ? ctx.getResources()
                    : ctx.getPackageManager().getResourcesForApplication(pkg);
            return res.getResourceEntryName(b.getInt("int1"));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Ícone de uma cor só (vetor de recurso ou bitmap cinza/branco): os ícones do modo carro
     * são feitos para o fundo escuro do Android Auto e precisam da cor do texto no tema claro.
     */
    @SuppressWarnings("deprecation")
    static boolean isMonochrome(CarBundle.Obj carIcon) {
        Bundle b = iconBundle(carIcon);
        if (b == null) return false;
        int type = b.getInt("type", -1);
        if (type == 2) return true;
        if (type != 1 && type != 5) return false;
        Object o = b.getParcelable("obj");
        if (!(o instanceof Bitmap)) return false;
        Bitmap bm = (Bitmap) o;
        int w = bm.getWidth(), h = bm.getHeight();
        if (w == 0 || h == 0) return false;
        int stepX = Math.max(1, w / 24), stepY = Math.max(1, h / 24);
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int c = bm.getPixel(x, y);
                if ((c >>> 24) < 64) continue;
                int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, bl = c & 0xff;
                if (Math.max(r, Math.max(g, bl)) - Math.min(r, Math.min(g, bl)) > 40) return false;
            }
        }
        return true;
    }

    private static Bundle iconBundle(CarBundle.Obj carIcon) {
        if (carIcon == null) return null;
        Object icon = carIcon.get("mIcon");
        return icon instanceof CarBundle.Image ? ((CarBundle.Image) icon).bundle : null;
    }

    @SuppressWarnings("deprecation")
    static Drawable fromIconCompat(Context ctx, Bundle b) {
        if (b == null) return null;
        try {
            int type = b.getInt("type", -1);
            Drawable d = null;
            switch (type) {
                case 1: // bitmap
                case 5: // bitmap adaptativo
                    Object bmp = b.getParcelable("obj");
                    if (bmp instanceof Bitmap) d = new BitmapDrawable(ctx.getResources(), (Bitmap) bmp);
                    break;
                case 2: { // recurso de outro pacote
                    String pkg = b.getString("string1");
                    int id = b.getInt("int1");
                    Resources res = pkg == null ? ctx.getResources()
                            : ctx.getPackageManager().getResourcesForApplication(pkg);
                    d = res.getDrawable(id);
                    break;
                }
                case 3: { // bytes
                    byte[] data = b.getByteArray("obj");
                    if (data != null) {
                        Bitmap bm = BitmapFactory.decodeByteArray(data, b.getInt("int1"), b.getInt("int2"));
                        if (bm != null) d = new BitmapDrawable(ctx.getResources(), bm);
                    }
                    break;
                }
                case 4:
                case 6: { // uri
                    String uri = b.getString("obj");
                    if (uri != null) {
                        InputStream in = ctx.getContentResolver().openInputStream(Uri.parse(uri));
                        Bitmap bm = BitmapFactory.decodeStream(in);
                        if (in != null) in.close();
                        if (bm != null) d = new BitmapDrawable(ctx.getResources(), bm);
                    }
                    break;
                }
                default:
                    break;
            }
            if (d != null && b.containsKey("tint_list")) {
                Object tl = b.getParcelable("tint_list");
                if (tl instanceof ColorStateList) {
                    d = d.mutate();
                    d.setColorFilter(((ColorStateList) tl).getDefaultColor(), PorterDuff.Mode.SRC_IN);
                }
            }
            return d;
        } catch (Exception e) {
            Log.w(CarSession.TAG, "ícone ilegível " + b, e);
            return null;
        }
    }
}
