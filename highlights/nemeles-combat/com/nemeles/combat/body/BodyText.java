package com.nemeles.combat.body;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

/**
 * Motor de texto "negative-space" para escribir texto LEGIBLE dentro del título de un inventario,
 * encima del wallpaper radiográfico (igual técnica que el teléfono: {@code com.nemeles.nemelesphone.ChatText}).
 *
 * <p>Reutiliza la fuente del teléfono {@code nemelesphone:chat}: define ascii.png en varios bloques de
 * codepoints, cada uno con un {@code ascent} distinto (= una "línea"). Render en la línea K → cada char
 * se remapea a codepoint (0xE000 + K*0x100 + cp), donde cp es la celda CP437 del glyph en ascii.png.
 * Los espacios se emiten en la fuente DEFAULT (avance real 4px), no en la de chat (cuya celda de espacio
 * se recorta a ~0). Esa fuente ya viaja en el pack combinado (extraído de NemelesPhone.zip).</p>
 */
public final class BodyText {

    public static final Key CHAT_FONT = Key.key("nemelesphone", "chat");
    public static final int MAX_LINES = 6;
    /** Línea reservada para la barra de estado superior (ascent alto en chat.json). */
    public static final int STATUS_LINE = 6;

    private BodyText() { }

    /** Anchos de avance (px) por byte CP437 (0x20–0xFF). Acentuadas ≈ 6px. */
    private static final int[] W = new int[256];
    static {
        for (int i = 0x20; i < 0x100; i++) W[i] = 6;
        int[] narrow2 = {'!', ',', '.', ':', ';', 'i', '|'};
        int[] narrow3 = {'l', '`', '\''};
        int[] narrow4 = {'t', 'I', '[', ']', ' '};   // ' ' = 4
        int[] narrow5 = {'"', '(', ')', '*', '<', '>', 'f', 'k', '{', '}'};
        int[] wide7    = {'@', '~'};
        for (int c : narrow2) W[c] = 2;
        for (int c : narrow3) W[c] = 3;
        for (int c : narrow4) W[c] = 4;
        for (int c : narrow5) W[c] = 5;
        for (int c : wide7)   W[c] = 7;
    }

    /** Traduce un char Unicode a su posición (byte) en ascii.png / CP437. -1 si no hay glyph. */
    private static int cp437(char c) {
        if (c >= 0x20 && c <= 0x7E) return c;
        switch (c) {
            case 'á': return 0xA0; case 'é': return 0x82; case 'í': return 0xA1;
            case 'ó': return 0xA2; case 'ú': return 0xA3; case 'ñ': return 0xA4;
            case 'ü': return 0x81; case 'ç': return 0x87;
            case 'à': return 0x85; case 'è': return 0x8A; case 'ì': return 0x8D;
            case 'ò': return 0x95; case 'ù': return 0x97;
            case 'â': return 0x83; case 'ê': return 0x88; case 'î': return 0x8C;
            case 'ô': return 0x93; case 'û': return 0x96;
            case 'ä': return 0x84; case 'ë': return 0x89; case 'ï': return 0x8B; case 'ö': return 0x94;
            case 'Ñ': return 0xA5; case 'Ç': return 0x80; case 'É': return 0x90;
            case 'Ü': return 0x9A; case 'Ö': return 0x99; case 'Ä': return 0x8E; case 'Å': return 0x8F;
            case 'Á': return 'A'; case 'Í': return 'I'; case 'Ó': return 'O'; case 'Ú': return 'U';
            case '¿': return 0xA8; case '¡': return 0xAD;
            case 'ª': return 0xA6; case 'º': return 0xA7;
            case '«': return 0xAE; case '»': return 0xAF;
            case '…': return '.';
            case '“': case '”': return '"';
            case '‘': case '’': return '\'';
            case '–': case '—': return '-';
            default:  return -1;   // sin glyph → descartar
        }
    }

    /** Ancho en píxeles del texto renderizado. */
    public static int width(String s) {
        if (s == null) return 0;
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            int b = cp437(s.charAt(i));
            if (b >= 0) w += W[b];
        }
        return w;
    }

    /**
     * Componente de texto en la línea K, con la fuente de chat y color dado.
     * Los espacios se emiten en la fuente DEFAULT (avance 4px) para que no colapsen.
     */
    public static Component line(String text, int line, TextColor color) {
        if (text == null || text.isEmpty()) return Component.empty();
        int base = 0xE000 + line * 0x100;
        Component out = Component.empty();
        StringBuilder run = new StringBuilder();
        int i = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == ' ') {
                if (run.length() > 0) {
                    out = out.append(Component.text(run.toString()).font(CHAT_FONT).color(color));
                    run.setLength(0);
                }
                int sp = 0;
                while (i < n && text.charAt(i) == ' ') { sp++; i++; }
                out = out.append(Component.text(" ".repeat(sp)));   // fuente default
            } else {
                int cp = cp437(c);
                if (cp >= 0) run.append((char) (base + cp));
                i++;
            }
        }
        if (run.length() > 0) out = out.append(Component.text(run.toString()).font(CHAT_FONT).color(color));
        return out;
    }
}
