package com.k4811.map.vector;

import static com.k4811.map.vector.MapSection.*;

/**
 * Cores, larguras e zooms do mapa offline. Cores fixas de mapa (claro e escuro), não as do tema da
 * suíte: o mapa precisa do mesmo contraste em qualquer cor de tema.
 */
final class MapStyle {

    final int land, water, park, forest, sand, building, buildingLine;
    final int rail, boundaryState, boundaryCity, text, textHalo, placeText, suburbText;
    /** Por tipo de via (índice = tipo, 1..11): preenchimento, contorno e cor nos zooms sem contorno. */
    final int[] roadFill = new int[12], roadCase = new int[12], roadLow = new int[12];

    static final MapStyle LIGHT = new MapStyle(false);
    static final MapStyle DARK = new MapStyle(true);

    private MapStyle(boolean dark) {
        if (!dark) {
            land = 0xFFF4F1EA;
            water = 0xFFA9CFE5;
            park = 0xFFCFEAC1;
            forest = 0xFFB7D8A6;
            sand = 0xFFF1E5C3;
            building = 0xFFE2DBD3;
            buildingLine = 0xFFD0C6BB;
            rail = 0xFF9C9C9C;
            boundaryState = 0xFFA387B3;
            boundaryCity = 0xFFBCA8C8;
            text = 0xFF3A3A3A;
            textHalo = 0xFFFFFFFF;
            placeText = 0xFF1F1F1F;
            suburbText = 0xFF6A6A6A;
            set(MOTORWAY, 0xFFF7B04F, 0xFFCF8A2E, 0xFFF0A040);
            set(TRUNK, 0xFFF9C066, 0xFFD39A40, 0xFFF0AE50);
            set(PRIMARY, 0xFFFCD98A, 0xFFD4B064, 0xFFE9C46E);
            set(SECONDARY, 0xFFFFFFFF, 0xFFCBC2B5, 0xFFD3CBBF);
            set(TERTIARY, 0xFFFFFFFF, 0xFFCFC7BB, 0xFFD8D1C6);
            set(MINOR, 0xFFFFFFFF, 0xFFD6CFC5, 0xFFDDD7CE);
            set(LIVING, 0xFFFFFFFF, 0xFFD6CFC5, 0xFFDDD7CE);
            set(SERVICE, 0xFFFFFFFF, 0xFFDCD5CC, 0xFFE2DCD4);
            set(PEDESTRIAN, 0xFFF1EEE8, 0xFFD6CFC5, 0xFFE2DCD4);
            set(TRACK, 0xFFB8A88C, 0, 0xFFB8A88C);
            set(PATH, 0xFFA59D93, 0, 0xFFA59D93);
        } else {
            land = 0xFF1E2227;
            water = 0xFF17324A;
            park = 0xFF22342A;
            forest = 0xFF24382B;
            sand = 0xFF37342A;
            building = 0xFF353A41;
            buildingLine = 0xFF41474F;
            rail = 0xFF676C73;
            boundaryState = 0xFF7C6A8A;
            boundaryCity = 0xFF5E5268;
            text = 0xFFD3D7DC;
            textHalo = 0xFF1E2227;
            placeText = 0xFFE6E9EC;
            suburbText = 0xFF9AA0A7;
            set(MOTORWAY, 0xFF8C6631, 0xFF5B4220, 0xFF7A592B);
            set(TRUNK, 0xFF846338, 0xFF574124, 0xFF735632);
            set(PRIMARY, 0xFF6C5E40, 0xFF473E2A, 0xFF5F533A);
            set(SECONDARY, 0xFF4A5058, 0xFF2D3137, 0xFF424850);
            set(TERTIARY, 0xFF464C54, 0xFF2D3137, 0xFF3F454C);
            set(MINOR, 0xFF40464E, 0xFF2B2F35, 0xFF3A4048);
            set(LIVING, 0xFF40464E, 0xFF2B2F35, 0xFF3A4048);
            set(SERVICE, 0xFF3B4148, 0xFF2B2F35, 0xFF363C43);
            set(PEDESTRIAN, 0xFF383E45, 0xFF2B2F35, 0xFF363C43);
            set(TRACK, 0xFF5A5446, 0, 0xFF5A5446);
            set(PATH, 0xFF555A61, 0, 0xFF555A61);
        }
    }

    private void set(int type, int fill, int casing, int low) {
        roadFill[type] = fill;
        roadCase[type] = casing;
        roadLow[type] = low;
    }

    static boolean isRoad(int type) {
        return type >= MOTORWAY && type <= PATH;
    }

    /** Zoom a partir do qual a feição aparece. */
    static int minZoom(int type) {
        switch (type) {
            case MOTORWAY:
            case TRUNK:
            case BOUNDARY_STATE:
            case CITY:
            case WATER:
            case OCEAN:
            case PARK:
            case FOREST:
            case SAND:
                return 7;
            case PRIMARY:
                return 8;
            case TOWN:
                return 9;
            case SECONDARY:
            case RAIL:
            case RIVER:
            case BOUNDARY_CITY:
                return 10;
            case TERTIARY:
                return 11;
            case MINOR:
            case LIGHT_RAIL:
            case VILLAGE:
            case SUBURB:
                return 12;
            case LIVING:
                return 13;
            case TRAM:
            case STREAM:
            case NEIGHBOURHOOD:
                return 14;
            case SERVICE:
            case PEDESTRIAN:
            case TRACK:
            case BUILDING:
                return 15;
            default:
                return 16;
        }
    }

    /** Zoom depois do qual o nome do lugar some (a área já é maior que a tela). */
    static int maxPlaceZoom(int type) {
        switch (type) {
            case CITY:
                return 13;
            case TOWN:
                return 14;
            case NEIGHBOURHOOD:
                return 17;
            default:
                return 16;
        }
    }

    /** Zoom a partir do qual o nome da via aparece. */
    static int labelZoom(int type) {
        if (type <= SECONDARY) return 13;
        if (type == TERTIARY) return 14;
        if (type <= LIVING) return 15;
        return 16;
    }

    /** Largura da via no zoom (em dp). */
    static float roadWidth(int type, boolean link, float z) {
        float base, min;
        switch (type) {
            case MOTORWAY: base = 9; min = 1.6f; break;
            case TRUNK: base = 8.5f; min = 1.5f; break;
            case PRIMARY: base = 8; min = 1.3f; break;
            case SECONDARY: base = 7; min = 1.1f; break;
            case TERTIARY: base = 6.5f; min = 1f; break;
            case MINOR: base = 5; min = 0.6f; break;
            case LIVING: base = 4.5f; min = 0.6f; break;
            case SERVICE: base = 3; min = 0.5f; break;
            case PEDESTRIAN: base = 3.5f; min = 0.5f; break;
            case TRACK: base = 1.8f; min = 0.6f; break;
            default: base = 1.2f; min = 0.6f; break;
        }
        if (link) base *= 0.65f;
        float k = (float) Math.pow(2, (z - 16) * 0.75);
        return Math.max(min, base * k);
    }

    /** Tamanho do nome do lugar (dp). */
    static float placeSize(int type) {
        switch (type) {
            case CITY: return 15;
            case TOWN: return 13.5f;
            case NEIGHBOURHOOD: return 11.5f;
            default: return 12.5f;
        }
    }
}
