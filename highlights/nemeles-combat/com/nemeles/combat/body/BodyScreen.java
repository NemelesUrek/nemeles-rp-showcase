package com.nemeles.combat.body;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

/**
 * Constructor de TÍTULOS para la ficha médica "radiográfica" a pantalla completa (técnica del teléfono,
 * {@code com.nemeles.nemelesphone.ScreenTitle}).
 *
 * <p>Pinta el wallpaper (radiografía, glyph de fondo de la fuente {@code nemeles:body}) y luego permite
 * escribir texto legible en líneas de cuerpo (bloques 0..5) y en la barra de estado superior (bloque
 * {@link BodyText#STATUS_LINE}). El wallpaper y los desplazamientos van en la fuente {@code nemeles:body};
 * el texto va en {@code nemelesphone:chat} (vía {@link BodyText}). Todo dentro del MISMO título.</p>
 *
 * <p>Mapeo bloque → fila de slots: bloque K (0..4) → fila de slots K+1. Margen izq. estándar: x = 16 px.</p>
 *
 * <p>Codepoints (PUA) definidos como aritmética para que el fuente sea ASCII puro (sin riesgo de
 * codificación). Deben COINCIDIR con la fuente {@code nemeles:body} (la genera draw_body_ui.ps1).</p>
 */
public final class BodyScreen {

    public static final Key BODY_FONT = Key.key("nemeles", "body");

    // wallpapers (bitmaps en nemeles:body, ascent 13 height 222)
    public static final char BG_OVERVIEW    = (char) 0xE020;
    /** detalle por región: BG_DETALLE_BASE + índice de región (0..9) → 0xE030..0xE039. */
    public static final char BG_DETALLE_BASE = (char) 0xE030;

    // tags invisibles (advance 0) para detectar la pantalla por título.
    // Fuera del rango de los glyphs de TEXTO (nemelesphone:chat usa E000..E6FF) y de los
    // offsets (E101..E280) para que indexOf(tag) no de falsos positivos.
    public static final char TAG_OVERVIEW = (char) 0xE7F0;
    public static final char TAG_DETALLE  = (char) 0xE7F1;

    private static final int       HORIZONTAL_OFFSET = -8;   // alinea wallpaper con la GUI
    private static final int       BG_WIDTH          = 176;
    public  static final int       LEFT_MARGIN       = 16;
    private static final TextColor WHITE             = TextColor.color(0xFFFFFF);

    // glyphs de avance potencia-de-2 de nemeles:body (espacios positivos/negativos), ordenados por POW
    private static final int[]  POW = {128, 64, 32, 16, 8, 4, 2, 1};
    private static final char[] POS = {
            (char) 0xE180, (char) 0xE140, (char) 0xE120, (char) 0xE110,
            (char) 0xE108, (char) 0xE104, (char) 0xE102, (char) 0xE101 };
    private static final char[] NEG = {
            (char) 0xE280, (char) 0xE240, (char) 0xE220, (char) 0xE210,
            (char) 0xE208, (char) 0xE204, (char) 0xE202, (char) 0xE201 };

    private Component t      = Component.empty();
    private int       cursor = 0;

    private BodyScreen(char bg, char tag) {
        t = t.append(Component.text(offset(HORIZONTAL_OFFSET)).font(BODY_FONT));
        cursor += HORIZONTAL_OFFSET;
        t = t.append(Component.text(String.valueOf(bg)).font(BODY_FONT).color(WHITE));
        cursor += BG_WIDTH;
        // tag de detección (advance 0) en la misma fuente
        t = t.append(Component.text(String.valueOf(tag)).font(BODY_FONT));
        resetCursor();
    }

    /** Empieza un título sobre el wallpaper {@code bg}, con el {@code tag} de detección. */
    public static BodyScreen of(char bg, char tag) { return new BodyScreen(bg, tag); }

    private void resetCursor() {
        if (cursor != 0) { t = t.append(Component.text(offset(-cursor)).font(BODY_FONT)); cursor = 0; }
    }

    /** Texto en la línea {@code block} (0..6) a una x dada (px desde el borde izq del wallpaper). */
    public BodyScreen line(int block, int x, String text, TextColor color) {
        resetCursor();
        if (x != 0) { t = t.append(Component.text(offset(x)).font(BODY_FONT)); cursor = x; }
        t = t.append(BodyText.line(text, block, color));
        cursor += BodyText.width(text);
        return this;
    }

    /** Texto de cuerpo con el margen izquierdo estándar (x = 16). */
    public BodyScreen line(int block, String text, TextColor color) {
        return line(block, LEFT_MARGIN, text, color);
    }

    /** Texto centrado horizontalmente en la línea {@code block}. */
    public BodyScreen centered(int block, String text, TextColor color) {
        int w = BodyText.width(text);
        int x = Math.max(0, (BG_WIDTH - w) / 2);
        return line(block, x, text, color);
    }

    /** Texto centrado en la barra de estado superior (bloque 6). */
    public BodyScreen status(String text, TextColor color) {
        return centered(BodyText.STATUS_LINE, text, color);
    }

    /**
     * Posa un PUNTO de gravedad (glyph blanco tintado) centrado sobre el slot (fila, col) del
     * overview. La fila elige el ascent (glyph 0xE040+fila); la col, el desplazamiento x.
     * Se tinta con {@code color} (rojo/naranja/ámbar/verde) — el punto blanco hereda el color.
     */
    public BodyScreen dot(int row, int col, TextColor color) {
        resetCursor();
        int x = 4 + col * 18;   // punto de 8px centrado en el slot (centro interno = 8 + col*18)
        t = t.append(Component.text(offset(x)).font(BODY_FONT));
        char glyph = (char) (0xE040 + Math.max(0, Math.min(5, row)));
        t = t.append(Component.text(String.valueOf(glyph)).font(BODY_FONT).color(color));
        cursor = x + 8;   // el punto avanza su anchura (img 8px), igual que el wallpaper
        return this;
    }

    /** ANILLO (círculo) de 14px sobre el slot (fila,col): marca la zona DAÑADA. Tintado por gravedad. */
    public BodyScreen ring(int row, int col, TextColor color) {
        resetCursor();
        int x = 1 + col * 18;   // anillo de 14px centrado en el slot (centro interno = 8 + col*18)
        t = t.append(Component.text(offset(x)).font(BODY_FONT));
        char glyph = (char) (0xE050 + Math.max(0, Math.min(5, row)));
        t = t.append(Component.text(String.valueOf(glyph)).font(BODY_FONT).color(color));
        cursor = x + 14;
        return this;
    }

    /** ANILLO GRANDE (44px) centrado sobre el hueso en la pantalla de DETALLE. Tintado por gravedad. */
    public BodyScreen ringBig(TextColor color) {
        resetCursor();
        int x = 58;   // centra el anillo de 44px en x=88 interno (centro lógico 80, izq 58)
        t = t.append(Component.text(offset(x)).font(BODY_FONT));
        t = t.append(Component.text(String.valueOf((char) 0xE058)).font(BODY_FONT).color(color));
        cursor = x + 44;
        return this;
    }

    public Component build() { return t; }

    /** Compone un desplazamiento horizontal en px sumando glyphs potencia-de-2 de nemeles:body. */
    public static String offset(int px) {
        if (px == 0) return "";
        boolean neg = px < 0;
        int abs = Math.abs(px);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < POW.length; i++) {
            while (abs >= POW[i]) { sb.append(neg ? NEG[i] : POS[i]); abs -= POW[i]; }
        }
        return sb.toString();
    }
}
