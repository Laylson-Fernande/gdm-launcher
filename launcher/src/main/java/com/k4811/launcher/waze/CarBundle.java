package com.k4811.launcher.waze;

import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcelable;

import androidx.car.app.serialization.Bundleable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lê e monta o formato do Bundler da androidx.car.app sem a biblioteca.
 *
 * Cada valor é um Bundle com tag_class_type (0 primitivo, 1 IInterface, 2 mapa, 3 set,
 * 4 lista, 5 objeto, 6 imagem, 7 enum, 8 classe, 9 IBinder, 10 Person) e tag_value. Objetos
 * têm tag_class_name e um campo por atributo, com a chave "nome.da.Classe" + "mCampo".
 */
public final class CarBundle {
    private static final String TYPE = "tag_class_type";
    private static final String VALUE = "tag_value";
    private static final String NAME = "tag_class_name";

    private CarBundle() {
    }

    /** Objeto do protocolo (ex.: androidx.car.app.model.Action) com os campos já lidos. */
    public static final class Obj {
        public final String cls;
        public final Map<String, Object> fields = new LinkedHashMap<>();

        Obj(String cls) {
            this.cls = cls;
        }

        public String simpleName() {
            return cls.substring(cls.lastIndexOf('.') + 1);
        }

        public boolean is(String simple) {
            return cls.endsWith("." + simple);
        }

        public Object get(String field) {
            return fields.get(field);
        }

        public Obj obj(String field) {
            Object o = fields.get(field);
            return o instanceof Obj ? (Obj) o : null;
        }

        @SuppressWarnings("unchecked")
        public List<Object> list(String field) {
            Object o = fields.get(field);
            return o instanceof List ? (List<Object>) o : Collections.emptyList();
        }

        public String str(String field) {
            Object o = fields.get(field);
            return o == null ? null : o.toString();
        }

        public int i(String field, int def) {
            Object o = fields.get(field);
            return o instanceof Number ? ((Number) o).intValue() : def;
        }

        public long l(String field, long def) {
            Object o = fields.get(field);
            return o instanceof Number ? ((Number) o).longValue() : def;
        }

        public double d(String field, double def) {
            Object o = fields.get(field);
            return o instanceof Number ? ((Number) o).doubleValue() : def;
        }

        public boolean b(String field) {
            return Boolean.TRUE.equals(fields.get(field));
        }

        /** Texto de um CarText (mText) ou de uma String. */
        public String text(String field) {
            Object o = fields.get(field);
            if (o instanceof Obj) return ((Obj) o).str("mText");
            return o == null ? null : o.toString();
        }

        @Override
        public String toString() {
            return dump(this, 0, new StringBuilder()).toString();
        }
    }

    /** Imagem (IconCompat.toBundle). */
    public static final class Image {
        public final Bundle bundle;

        Image(Bundle bundle) {
            this.bundle = bundle;
        }
    }

    /** IInterface do outro lado: binder + nome da interface. */
    public static final class Iface {
        public final IBinder binder;
        public final String cls;

        Iface(IBinder binder, String cls) {
            this.binder = binder;
            this.cls = cls;
        }
    }

    public static Object decode(Bundleable b) {
        return b == null || b.bundle == null ? null : decode(b.bundle);
    }

    @SuppressWarnings("deprecation")
    public static Object decode(Bundle b) {
        b.setClassLoader(CarBundle.class.getClassLoader());
        switch (b.getInt(TYPE)) {
            case 0:
                return b.get(VALUE);
            case 1:
                return new Iface(b.getBinder(VALUE), b.getString(NAME));
            case 9:
                return b.getBinder(VALUE);
            case 2: {
                Map<Object, Object> m = new LinkedHashMap<>();
                ArrayList<Parcelable> entries = b.getParcelableArrayList(VALUE);
                if (entries != null) {
                    for (Parcelable p : entries) {
                        Bundle e = (Bundle) p;
                        Bundle k = e.getBundle("tag_1");
                        Bundle v = e.getBundle("tag_2");
                        m.put(k == null ? null : decode(k), v == null ? null : decode(v));
                    }
                }
                return m;
            }
            case 3:
            case 4: {
                List<Object> l = new ArrayList<>();
                ArrayList<Parcelable> items = b.getParcelableArrayList(VALUE);
                if (items != null) {
                    for (Parcelable p : items) l.add(decode((Bundle) p));
                }
                return l;
            }
            case 5: {
                String cls = b.getString(NAME);
                Obj o = new Obj(cls);
                for (String key : b.keySet()) {
                    if (TYPE.equals(key) || NAME.equals(key)) continue;
                    Object v = b.get(key);
                    if (!(v instanceof Bundle)) continue;
                    o.fields.put(fieldName(cls, key), decode((Bundle) v));
                }
                return o;
            }
            case 6:
                return new Image(b.getBundle(VALUE));
            case 7:
                return b.getString(VALUE);
            case 8:
                return b.getString(VALUE);
            default:
                return null;
        }
    }

    /** "androidx.car.app.model.ActionmTitle" -> "mTitle" (campos de superclasse: melhor esforço). */
    static String fieldName(String cls, String key) {
        if (cls != null && key.startsWith(cls) && key.length() > cls.length()) {
            return key.substring(cls.length());
        }
        int dot = key.lastIndexOf('.');
        String tail = dot < 0 ? key : key.substring(dot + 1);
        for (int i = tail.length() - 2; i > 0; i--) {
            if (tail.charAt(i) == 'm' && Character.isUpperCase(tail.charAt(i + 1))
                    && Character.isLowerCase(tail.charAt(i - 1))) {
                return tail.substring(i);
            }
        }
        return tail;
    }

    // ---- montar (host -> app) ----

    public static Bundle primitive(Object v) {
        Bundle b = new Bundle();
        b.putInt(TYPE, 0);
        if (v instanceof Integer) b.putInt(VALUE, (Integer) v);
        else if (v instanceof Boolean) b.putBoolean(VALUE, (Boolean) v);
        else if (v instanceof String) b.putString(VALUE, (String) v);
        else if (v instanceof Long) b.putLong(VALUE, (Long) v);
        else if (v instanceof Float) b.putFloat(VALUE, (Float) v);
        else if (v instanceof Double) b.putDouble(VALUE, (Double) v);
        else if (v instanceof Parcelable) b.putParcelable(VALUE, (Parcelable) v);
        else throw new IllegalArgumentException("tipo " + v);
        return b;
    }

    /** Objeto: pares nome do campo, valor (primitivo, Parcelable ou Bundle já montado). */
    public static Bundle object(String cls, Object... kv) {
        Bundle b = new Bundle();
        b.putInt(TYPE, 5);
        b.putString(NAME, cls);
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            if (v == null) continue;
            b.putParcelable(cls + kv[i], v instanceof Bundle ? (Bundle) v : primitive(v));
        }
        return b;
    }

    public static Bundleable wrap(Bundle b) {
        return new Bundleable(b);
    }

    private static StringBuilder dump(Object o, int depth, StringBuilder sb) {
        String pad = new String(new char[depth * 2]).replace('\0', ' ');
        if (o instanceof Obj) {
            Obj obj = (Obj) o;
            sb.append(obj.simpleName()).append(" {\n");
            for (Map.Entry<String, Object> e : obj.fields.entrySet()) {
                sb.append(pad).append("  ").append(e.getKey()).append(" = ");
                dump(e.getValue(), depth + 1, sb).append('\n');
            }
            sb.append(pad).append('}');
        } else if (o instanceof List) {
            sb.append("[\n");
            for (Object e : (List<?>) o) {
                sb.append(pad).append("  ");
                dump(e, depth + 1, sb).append('\n');
            }
            sb.append(pad).append(']');
        } else if (o instanceof Image) {
            sb.append("Image").append(((Image) o).bundle);
        } else if (o instanceof Iface) {
            sb.append("Iface(").append(((Iface) o).cls).append(')');
        } else {
            sb.append(o);
        }
        return sb;
    }
}
