package com.k4811.launcher.waze;

/** CarColor -> ARGB, nos tons da tela (dia e noite). */
final class Colors {
    private Colors() {
    }

    /**
     * Cor de ícone: o Waze pinta os ícones de branco (fundo escuro do Android Auto). Branco,
     * quase branco ou a cor padrão viram a cor do texto do tema; as outras cores ficam.
     */
    static int icon(CarBundle.Obj carColor, boolean dark, int text) {
        if (carColor == null) return text;
        int type = carColor.i("mType", 1);
        if (type == 1) return text;
        if (type == 0) {
            int c = dark ? carColor.i("mColorDark", carColor.i("mColor", 0)) : carColor.i("mColor", 0);
            int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
            if (r > 200 && g > 200 && b > 200) return text;
            if (r < 40 && g < 40 && b < 40) return text;
        }
        return of(carColor, dark, text);
    }

    /** CarColor.mType: 0 custom, 1 padrão, 2 primária, 3 secundária, 4 vermelho, 5 verde, 6 azul, 7 amarelo. */
    static int of(CarBundle.Obj carColor, boolean dark, int def) {
        if (carColor == null) return def;
        switch (carColor.i("mType", 1)) {
            case 0:
                return dark ? carColor.i("mColorDark", carColor.i("mColor", def)) : carColor.i("mColor", def);
            case 2:
                return dark ? 0xff8ab4f8 : 0xff1a5fc8;
            case 3:
            case 4:
                return dark ? 0xfff28b82 : 0xffc62828;
            case 5:
                return dark ? 0xff81c995 : 0xff1b7a35;
            case 6:
                return dark ? 0xff8ab4f8 : 0xff1a5fc8;
            case 7:
                return dark ? 0xfffdd663 : 0xffb06d00;
            default:
                return def;
        }
    }
}
