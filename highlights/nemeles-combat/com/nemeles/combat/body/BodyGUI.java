package com.nemeles.combat.body;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FICHA MÉDICA "RADIOGRAFÍA" v5 — pantalla diegética estilo teléfono (NO se lee como cofre): un
 * wallpaper de ESCÁNER ÓSEO ocupa el título sobre slots transparentes; cada parte lesionada se
 * marca con un PUNTO de gravedad, y al pulsarla se abre su RADIOGRAFÍA de detalle con el informe.
 *
 * <p>VISIÓN GRADUAL: lo que ves depende de TU formación (curso 0/1/2). Un lego solo intuye que algo
 * va mal; un médico ve el dato exacto, las condiciones y el tratamiento. Crossplay (solo items
 * vanilla con item_model transparente + fuente custom en el título; sin emojis en el lore).</p>
 */
public final class BodyGUI {

    // ── transparencia (reusa el modelo del teléfono, ya mapeado en Geyser para Bedrock) ──
    private static final NamespacedKey TRANSPARENT = NamespacedKey.minecraft("nemelesphone/transparent");

    // ── colores (título = Adventure TextColor; lore = ChatColor) ──
    private static final TextColor C_TITLE = TextColor.color(0xC8DCEC);
    private static final TextColor C_HINT  = TextColor.color(0x9FC4D6);
    private static final TextColor T_LEVE  = TextColor.color(0xE6C34A);
    private static final TextColor T_SERIO = TextColor.color(0xE08A2E);
    private static final TextColor T_CRIT  = TextColor.color(0xE0443A);
    private static final TextColor T_INFEC = TextColor.color(0x5FA63C);
    private static final TextColor T_SANO  = TextColor.color(0x4CD964);   // verde "hueso bien"

    // ── slots ──
    public static final int SLOT_CLOSE_OV = 53;     // overview: cerrar
    public static final int SLOT_CLOSE_D  = 8;      // detalle: cerrar
    public static final int SLOT_TREAT    = 49;     // detalle: tratar
    public static final int SLOT_BACK     = 53;     // detalle: volver

    /** viewer -> paciente que está mirando. */
    public static final Map<UUID, UUID> VIEWING = new ConcurrentHashMap<>();
    /** viewer -> parte abierta en la pantalla de detalle. */
    public static final Map<UUID, BodyPart> VIEWING_PART = new ConcurrentHashMap<>();

    // disposición anatómica en el cofre de 54 (cabeza arriba, pies abajo) — DEBE coincidir con el wallpaper
    private static final Map<Integer, BodyPart> SLOTS = Map.ofEntries(
            Map.entry(4, BodyPart.CABEZA),
            Map.entry(13, BodyPart.CUELLO),
            Map.entry(21, BodyPart.BRAZO_DER), Map.entry(22, BodyPart.TORSO_SUP), Map.entry(23, BodyPart.BRAZO_IZQ),
            Map.entry(30, BodyPart.MANO_DER), Map.entry(31, BodyPart.TORSO_INF), Map.entry(32, BodyPart.MANO_IZQ),
            Map.entry(40, BodyPart.INGLE),
            Map.entry(39, BodyPart.MUSLO_DER), Map.entry(41, BodyPart.MUSLO_IZQ),
            Map.entry(48, BodyPart.ESPINILLA_DER), Map.entry(50, BodyPart.ESPINILLA_IZQ),
            Map.entry(47, BodyPart.PIE_DER), Map.entry(51, BodyPart.PIE_IZQ)
    );

    private BodyGUI() { }

    public static BodyPart partAt(int slot) { return SLOTS.get(slot); }

    /** Jugador de Bedrock (Floodgate). Reflexión-safe (delega en BedrockBodyForm): false si Floodgate
     *  no está en runtime. La GUI radiográfica (fuente custom + slots transparentes) NO se renderiza en
     *  Bedrock (Geyser), así que se le sirve un SimpleForm nativo y, si no, la ficha por CHAT. */
    public static boolean isBedrock(Player p) { return BedrockBodyForm.isBedrock(p); }

    /** Punto de entrada: GUI radiográfica en Java; ficha NATIVA (SimpleForm) en Bedrock con caída a
     *  CHAT clicable; y siempre el CHAT si estás DERRIBADO (la GUI de inventario está bloqueada al
     *  caer, pero los comandos no: así puedes intentar atenderte tú mismo en el suelo). */
    public static void open(Player viewer, Player patient, BodyManager mgr) {
        // Para examinar a OTRO herido necesitas un BOTIQUÍN (kit de primeros auxilios). A ti mismo
        // siempre puedes mirarte (incluido derribado), para no bloquear el autoauxilio.
        boolean self = viewer.getUniqueId().equals(patient.getUniqueId());
        if (!self && !viewer.isOp() && !com.nemeles.combat.MedItems.has(viewer, com.nemeles.combat.MedItems.MEDKIT)) {
            viewer.sendMessage(ChatColor.RED + "Necesitas un " + ChatColor.GREEN + "Botiquín"
                    + ChatColor.RED + " para examinar a otro herido. " + ChatColor.GRAY + "(farmacia · o /medicina dar medkit)");
            return;
        }
        // Java SIEMPRE la radiografía (incluido derribado: el bloqueo de inventario exime la ficha).
        // Bedrock: SimpleForm nativo (Geyser no renderiza el wallpaper de fuente); si Floodgate/Cumulus
        // no están en runtime, BedrockBodyForm.open devuelve false y caemos al CHAT clicable (último recurso).
        if (isBedrock(viewer)) {
            if (!BedrockBodyForm.open(viewer, patient, mgr)) openChat(viewer, patient, mgr);
        } else {
            openOverview(viewer, patient, mgr);
        }
    }

    // ═══════════════════════ FICHA POR CHAT (Bedrock / universal) ═══════════════════════
    private static final BodyPart[] ORDER = {
            BodyPart.CABEZA, BodyPart.CUELLO, BodyPart.TORSO_SUP, BodyPart.TORSO_INF, BodyPart.INGLE,
            BodyPart.BRAZO_DER, BodyPart.BRAZO_IZQ, BodyPart.MANO_DER, BodyPart.MANO_IZQ,
            BodyPart.MUSLO_DER, BodyPart.MUSLO_IZQ, BodyPart.ESPINILLA_DER, BodyPart.ESPINILLA_IZQ,
            BodyPart.PIE_DER, BodyPart.PIE_IZQ };
    private static final TextColor CC_GRAY = TextColor.color(0xAAAAAA);
    private static final TextColor CC_DGRAY = TextColor.color(0x666666);
    private static final TextColor CC_GREEN = TextColor.color(0x5FD15F);
    private static final TextColor CC_AQUA = TextColor.color(0x57C7E0);

    public static void openChat(Player viewer, Player patient, BodyManager mgr) {
        VIEWING.put(viewer.getUniqueId(), patient.getUniqueId());
        VIEWING_PART.remove(viewer.getUniqueId());
        boolean self = viewer.getUniqueId().equals(patient.getUniqueId());
        int course = mgr.courseOf(viewer);
        int lvl = mgr.medLevel(viewer);
        int vlvl = course == 0 ? 0 : course == 1 ? 1 : 2;
        EnumMap<BodyPart, BodyManager.PartState> body = mgr.body(patient.getUniqueId());

        viewer.sendMessage(Component.text(""));
        viewer.sendMessage(Component.text("➕ FICHA MEDICA · " + (self ? "TU CUERPO" : patient.getName().toUpperCase())).color(C_TITLE));
        double pain = mgr.painOf(patient.getUniqueId());
        if (course >= 1) viewer.sendMessage(Component.text("   Dolor: " + painWord(pain)).color(CC_GRAY));
        boolean any = false;
        for (BodyPart part : ORDER) {
            BodyManager.PartState s = body.get(part);
            if (s == null || !s.any()) continue;
            any = true;
            String cond = primaryCondition(s);
            Component line = Component.text(" ▸ ").color(CC_DGRAY)
                    .append(Component.text(capitalize(part.display)).color(tierColor(cond)))
                    .append(Component.text(course >= 2 ? "  [" + (int) s.hp + "/100]" : "  (" + hpWord(s.hp) + ")").color(CC_GRAY))
                    .append(Component.text("  "))
                    .append(btn("[Ver]", CC_AQUA, "/cuerpo ver " + part.name(), "Ver el detalle de " + part.display));
            if (course >= 1) line = line.append(Component.text(" ")).append(btn("[Tratar]", CC_GREEN, "/cuerpo tratar " + part.name(), "Atender " + part.display));
            viewer.sendMessage(line);
            String txt = VisionTexts.get(cond, vlvl);
            if (txt != null) viewer.sendMessage(Component.text("     " + txt).color(vlvl == 0 ? CC_GRAY : C_HINT));
        }
        if (!any) viewer.sendMessage(Component.text("   Sin lesiones. Estás de una pieza.").color(CC_GREEN));
        else if (course == 0) viewer.sendMessage(Component.text("   Sin formación solo intuyes que está malherido.").color(CC_DGRAY));
        viewer.sendMessage(Component.text("   (pulsa los botones o usa /cuerpo)").color(CC_DGRAY));
        viewer.sendMessage(Component.text(""));
    }

    /** Detalle por CHAT de una parte (lo invoca /cuerpo ver <PART>). */
    public static void chatDetail(Player viewer, Player patient, BodyPart part, BodyManager mgr) {
        VIEWING.put(viewer.getUniqueId(), patient.getUniqueId());
        VIEWING_PART.put(viewer.getUniqueId(), part);
        boolean self = viewer.getUniqueId().equals(patient.getUniqueId());
        int course = mgr.courseOf(viewer);
        int lvl = mgr.medLevel(viewer);
        BodyManager.PartState s = mgr.body(patient.getUniqueId()).get(part);

        viewer.sendMessage(Component.text(""));
        viewer.sendMessage(Component.text("➕ " + capitalize(part.display).toUpperCase() + " · " + regionName(part)).color(C_TITLE));
        for (String ln : reportLines(part, s, course, lvl, self)) viewer.sendMessage(Component.text(ln).color(CC_GRAY));
        Component actions = btn("[< Volver]", CC_AQUA, "/cuerpo", "Volver a la ficha");
        if (s != null && s.any() && course >= 1)
            actions = actions.append(Component.text("  ")).append(btn("[Tratar]", CC_GREEN, "/cuerpo tratar " + part.name(), "Atender " + part.display));
        viewer.sendMessage(actions);
        viewer.sendMessage(Component.text(""));
    }

    /** Informe (líneas de texto plano) reutilizado por la GUI (lore) y por el chat. */
    private static java.util.List<String> reportLines(BodyPart part, BodyManager.PartState s, int course, int lvl, boolean self) {
        int vlvl = course == 0 ? 0 : course == 1 ? 1 : 2;
        java.util.List<String> out = new ArrayList<>();
        if (s == null || !s.any()) { out.add("  Sin lesiones que tratar."); return out; }
        out.add("  Integridad: " + (course >= 2 ? (int) s.hp + "/100" : hpWord(s.hp)));
        String txt = VisionTexts.get(primaryCondition(s), vlvl);
        if (txt != null) { out.add("  Diagnóstico:"); for (String l : wrap(txt, 44)) out.add("   " + l); }
        if (vlvl == 2) { out.add("  Condiciones:"); for (String c : conditionList(s)) out.add("   " + c); }
        if (vlvl == 0) { out.add("  Sin formación no sabrías cómo tratarla."); }
        else { out.add("  Tratamiento:"); for (String t : treatmentSteps(s, course, self)) out.add("   - " + t); }
        return out;
    }

    private static Component btn(String label, TextColor color, String cmd, String hover) {
        return Component.text(label).color(color)
                .clickEvent(ClickEvent.runCommand(cmd))
                .hoverEvent(HoverEvent.showText(Component.text(hover)));
    }

    // ═══════════════════════ OVERVIEW (escáner óseo) ═══════════════════════
    public static void openOverview(Player viewer, Player patient, BodyManager mgr) {
        VIEWING.put(viewer.getUniqueId(), patient.getUniqueId());
        VIEWING_PART.remove(viewer.getUniqueId());
        boolean self = viewer.getUniqueId().equals(patient.getUniqueId());
        int course = mgr.courseOf(viewer);
        int lvl = mgr.medLevel(viewer);
        EnumMap<BodyPart, BodyManager.PartState> body = mgr.body(patient.getUniqueId());

        BodyScreen st = BodyScreen.of(BodyScreen.BG_OVERVIEW, BodyScreen.TAG_OVERVIEW);
        st.status((self ? "TU CUERPO" : truncate(patient.getName(), 14).toUpperCase()) + "  ESCANER OSEO", C_TITLE);
        // dolor general (solo con algo de formación se cuantifica)
        double pain = mgr.painOf(patient.getUniqueId());
        if (course >= 1) st.line(4, BodyScreen.LEFT_MARGIN, "Dolor: " + painWord(pain), painColor(pain));
        // ESTADO POR HUESO: verde (punto) = sano · anillo de color sobre la zona = dañado (por gravedad)
        for (Map.Entry<Integer, BodyPart> e : SLOTS.entrySet()) {
            BodyManager.PartState s = body.get(e.getValue());
            int slot = e.getKey(), col = slot % 9, row = slot / 9;
            if (s != null && s.any()) st.ring(row, col, tierColor(primaryCondition(s)));
            else st.dot(row, col, T_SANO);
        }

        Inventory inv = Bukkit.createInventory(null, 54, st.build());
        ItemStack blank = transparent(" ", null);
        for (int i = 0; i < 54; i++) inv.setItem(i, blank);
        for (Map.Entry<Integer, BodyPart> e : SLOTS.entrySet())
            inv.setItem(e.getKey(), partTooltip(e.getValue(), body.get(e.getValue()), course, lvl));
        inv.setItem(SLOT_CLOSE_OV, transparent(ChatColor.RED + "" + ChatColor.BOLD + "Cerrar ficha", null));
        viewer.openInventory(inv);
    }

    /** Tooltip de cada zona en el overview (visión gradual + invitación a examinar). */
    private static ItemStack partTooltip(BodyPart part, BodyManager.PartState s, int course, int lvl) {
        int vlvl = course == 0 ? 0 : course == 1 ? 1 : 2;
        List<String> lore = new ArrayList<>();
        ChatColor nameCol;
        String tag;
        if (s == null || !s.any()) {
            nameCol = ChatColor.GREEN; tag = ChatColor.DARK_GRAY + "  sana";
            lore.add(ChatColor.DARK_GRAY + "Sin lesiones.");
        } else {
            String cond = primaryCondition(s);
            nameCol = tierChat(cond);
            tag = ChatColor.GRAY + "  " + (course >= 2 ? "[" + (int) s.hp + "/100]" : "(" + hpWord(s.hp) + ")");
            String txt = VisionTexts.get(cond, vlvl);
            if (txt != null) for (String ln : wrap(txt, 36)) lore.add((vlvl == 0 ? ChatColor.GRAY : ChatColor.WHITE) + ln);
            if (vlvl == 2) { lore.add(""); for (String c : conditionList(s)) lore.add(ChatColor.GRAY + c); }
        }
        lore.add("");
        lore.add((s != null && s.any() && course == 0)
                ? ChatColor.DARK_GRAY + "No sabrías ni por dónde empezar."
                : ChatColor.YELLOW + "> Pulsa para examinarla");
        return transparent(nameCol + capitalize(part.display) + tag, lore);
    }

    // ═══════════════════════ DETALLE (radiografía de la parte) ═══════════════════════
    public static void openDetail(Player viewer, Player patient, BodyPart part, BodyManager mgr) {
        VIEWING.put(viewer.getUniqueId(), patient.getUniqueId());
        VIEWING_PART.put(viewer.getUniqueId(), part);
        boolean self = viewer.getUniqueId().equals(patient.getUniqueId());
        int course = mgr.courseOf(viewer);
        int lvl = mgr.medLevel(viewer);
        BodyManager.PartState s = mgr.body(patient.getUniqueId()).get(part);

        char bg = (char) (BodyScreen.BG_DETALLE_BASE + regionIndex(part));
        BodyScreen st = BodyScreen.of(bg, BodyScreen.TAG_DETALLE);
        st.status(capitalize(part.display).toUpperCase(), C_TITLE);
        // CÍRCULO de color sobre el hueso = zona dañada (por gravedad); si está sana, no se marca.
        if (s != null && s.any()) st.ringBig(tierColor(primaryCondition(s)));
        st.centered(4, "[ TRATAR ]", s != null && s.any() ? C_TITLE : C_HINT);   // sobre el botón (slot 49)
        st.line(4, 142, "VOLVER", C_HINT);

        Inventory inv = Bukkit.createInventory(null, 54, st.build());
        ItemStack report = reportItem(part, s, course, lvl, self);
        for (int i = 0; i < 54; i++) inv.setItem(i, report);
        inv.setItem(SLOT_CLOSE_D, transparent(ChatColor.RED + "" + ChatColor.BOLD + "Cerrar", null));
        inv.setItem(SLOT_BACK, transparent(ChatColor.YELLOW + "" + ChatColor.BOLD + "< Volver al cuerpo", null));
        inv.setItem(SLOT_TREAT, treatButton(part, s, course, self));
        viewer.openInventory(inv);
    }

    /** Informe radiológico (lore) de la parte: integridad, diagnóstico y tratamiento, graduado. */
    private static ItemStack reportItem(BodyPart part, BodyManager.PartState s, int course, int lvl, boolean self) {
        int vlvl = course == 0 ? 0 : course == 1 ? 1 : 2;
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + "Radiografía · " + regionName(part));
        lore.add("");
        // INTEGRIDAD
        if (s == null || !s.any()) {
            lore.add(ChatColor.GREEN + "INTEGRIDAD: " + ChatColor.WHITE + "sana");
            lore.add(ChatColor.DARK_GRAY + "Sin lesiones que tratar.");
            return transparent(ChatColor.GREEN + capitalize(part.display), lore);
        }
        String cond = primaryCondition(s);
        ChatColor sevCol = tierChat(cond);
        lore.add(ChatColor.GRAY + "INTEGRIDAD: " + sevCol + integrityBar(s.hp)
                + (course >= 2 ? ChatColor.GRAY + " " + (int) s.hp + "/100" : ChatColor.GRAY + " " + hpWord(s.hp)));
        lore.add("");
        // DIAGNÓSTICO (gradual)
        lore.add(ChatColor.GRAY + "DIAGNOSTICO:");
        String txt = VisionTexts.get(cond, vlvl);
        if (txt != null) for (String ln : wrap(txt, 38)) lore.add((vlvl == 0 ? ChatColor.GRAY : ChatColor.WHITE) + " " + ln);
        if (vlvl == 2) for (String c : conditionList(s)) lore.add(ChatColor.DARK_GRAY + " " + c);
        lore.add("");
        // TRATAMIENTO
        if (vlvl == 0) {
            lore.add(ChatColor.DARK_GRAY + "Sin formación no sabrías cómo tratarla.");
            lore.add(ChatColor.DARK_GRAY + "Estudia: /medicina estudiar basico");
        } else {
            lore.add(ChatColor.GRAY + "TRATAMIENTO:");
            for (String t : treatmentSteps(s, course, self)) lore.add(ChatColor.AQUA + " " + t);
            lore.add("");
            lore.add(ChatColor.YELLOW + "> Botón TRATAR (abajo) para atenderla");
        }
        return transparent(sevCol + capitalize(part.display), lore);
    }

    private static ItemStack treatButton(BodyPart part, BodyManager.PartState s, int course, boolean self) {
        List<String> lore = new ArrayList<>();
        if (s == null || !s.any()) {
            lore.add(ChatColor.DARK_GRAY + "Esta parte está sana.");
            return transparent(ChatColor.GREEN + "Sin nada que tratar", lore);
        }
        if (course == 0) {
            lore.add(ChatColor.RED + "Necesitas formación médica.");
            lore.add(ChatColor.GRAY + "/medicina estudiar basico");
            return transparent(ChatColor.RED + "No puedes tratar", lore);
        }
        for (String t : treatmentSteps(s, course, self)) lore.add(ChatColor.AQUA + " " + t);
        lore.add("");
        lore.add(ChatColor.YELLOW + "Clic para atender " + capitalize(part.display));
        lore.add(ChatColor.DARK_GRAY + "Lo serio se cura sosteniendo (agáchate y quieto).");
        return transparent(ChatColor.GREEN + "" + ChatColor.BOLD + "TRATAR", lore);
    }

    // ═══════════════════════ clasificación clínica ═══════════════════════
    /** Condición DOMINANTE de la parte (por urgencia clínica) -> clave de los textos graduales. */
    public static String primaryCondition(BodyManager.PartState s) {
        if (s.infectado) return "INFECCION";
        if (s.bleeding >= 0.8) return "SANGRADO_GRAVE";
        if (s.bleeding > 0) return "SANGRADO";
        if (s.balaDentro) return s.balaProfunda ? "BALA_PROFUNDA" : "BALA";
        if (s.corteProfundo) return "CORTE_PROFUNDO";
        if (s.fractura >= 3) return "FRACTURA_3";
        if (s.fractura == 2) return "FRACTURA_2";
        if (s.fractura == 1) return "FRACTURA_1";
        if (s.quemadura >= 2) return "QUEMADURA_2";
        if (s.quemadura == 1) return "QUEMADURA_1";
        if (s.esguince >= 2) return "ESGUINCE_2";
        if (s.esguince == 1) return "ESGUINCE_1";
        if (s.contusion >= 3) return "CONTUSION_3";
        if (s.contusion == 2) return "CONTUSION_2";
        if (s.contusion == 1) return "CONTUSION_1";
        if (s.infectionRisk >= 0.10) return "INFECCION_RIESGO";
        return "CONTUSION";
    }

    /** Gravedad -> color (4 niveles + verde de infección). */
    private static TextColor tierColor(String cond) {
        switch (tierOf(cond)) {
            case 3: return T_CRIT; case 4: return T_INFEC; case 2: return T_SERIO; default: return T_LEVE;
        }
    }
    private static ChatColor tierChat(String cond) {
        switch (tierOf(cond)) {
            case 3: return ChatColor.RED; case 4: return ChatColor.DARK_GREEN; case 2: return ChatColor.GOLD; default: return ChatColor.YELLOW;
        }
    }
    /** 1 leve, 2 serio, 3 crítico, 4 infectado. */
    private static int tierOf(String cond) {
        switch (cond) {
            case "INFECCION": return 4;
            case "SANGRADO_GRAVE": case "FRACTURA_3": case "BALA_PROFUNDA": return 3;
            case "SANGRADO": case "BALA": case "CORTE_PROFUNDO": case "FRACTURA_2":
            case "QUEMADURA_2": case "ESGUINCE_2": case "INFECCION_RIESGO": case "CONTUSION_3": return 2;
            default: return 1;
        }
    }

    /** Lista técnica de TODAS las condiciones presentes (sin emojis: Bedrock no los pinta). */
    private static List<String> conditionList(BodyManager.PartState s) {
        List<String> l = new ArrayList<>();
        if (s.bleeding >= 0.8) l.add("- Sangrado GRAVE -> venda esteril/torniquete");
        else if (s.bleeding > 0) l.add("- Sangrado -> venda");
        if (s.balaDentro) l.add(s.balaProfunda ? "- Bala PROFUNDA -> bisturi+sutura" : "- Bala -> pinzas");
        if (s.corteProfundo) l.add("- Corte profundo -> sutura");
        if (s.fractura == 1) l.add("- Fisura -> ferula");
        else if (s.fractura == 2) l.add("- Fractura -> ferula+analgesico");
        else if (s.fractura >= 3) l.add("- Fractura ABIERTA -> otro medico");
        if (s.esguince > 0) l.add("- Esguince -> venda y reposo");
        if (s.contusion == 1) l.add("- Raspón -> limpiar y venda");
        else if (s.contusion == 2) l.add("- Moratón -> reposo y analgésico");
        else if (s.contusion >= 3) l.add("- Contusión fuerte -> reposo, venda y analgésico");
        if (s.quemadura > 0) l.add("- Quemadura -> pomada");
        if (s.infectado) l.add("- INFECTADA -> antibioticos");
        else if (s.infectionRisk >= 0.10) l.add("- Herida sucia -> desinfectante");
        if (s.vendada) l.add("- Vendada (sanando)");
        return l;
    }

    /** Pasos de tratamiento recomendados (lo que pide la condición dominante), gradual por curso. */
    private static List<String> treatmentSteps(BodyManager.PartState s, int course, boolean self) {
        List<String> l = new ArrayList<>();
        if (s.bleeding >= 0.8) l.add(course >= 1 ? "Venda esteril o torniquete (brazo/pierna)" : "Cierra el sangrado: venda");
        else if (s.bleeding > 0) l.add("Venda para cortar el sangrado");
        if (s.infectado) l.add(course >= 2 ? "Antibioticos" : "Pide un medico: antibioticos");
        if (s.balaDentro) l.add(course >= 2 ? (s.balaProfunda ? "Bisturi + sutura (otro medico)" : "Pinzas para extraer") : "Pide un medico: extraer la bala");
        if (s.corteProfundo) l.add(course >= 2 ? "Kit de sutura" : "Pide un medico: sutura");
        if (s.fractura == 1) l.add("Ferula");
        else if (s.fractura == 2) l.add("Ferula + analgesico");
        else if (s.fractura >= 3) l.add("Fractura abierta: otro medico avanzado");
        if (s.quemadura > 0) l.add("Pomada");
        if (s.esguince > 0) l.add("Venda de compresion y reposo");
        if (s.contusion >= 2) l.add("Reposo y analgesico (venda si hincha)");
        else if (s.contusion == 1) l.add("Limpiar y vendar el raspon");
        if (!s.infectado && s.infectionRisk >= 0.10) l.add("Desinfectante (antes de que se infecte)");
        if (l.isEmpty()) l.add("Venda y reposo para que regenere");
        return l;
    }

    // ═══════════════════════ regiones (wallpaper de detalle) ═══════════════════════
    /** Índice de región -> glyph de detalle (0 craneo .. 9 pie). I/D comparten radiografía. */
    private static int regionIndex(BodyPart p) {
        switch (p) {
            case CABEZA: return 0;
            case CUELLO: return 1;
            case TORSO_SUP: return 2;
            case TORSO_INF: return 3;
            case INGLE: return 4;
            case BRAZO_IZQ: case BRAZO_DER: return 5;
            case MANO_IZQ: case MANO_DER: return 6;
            case MUSLO_IZQ: case MUSLO_DER: return 7;
            case ESPINILLA_IZQ: case ESPINILLA_DER: return 8;
            default: return 9;   // PIE_*
        }
    }
    private static String regionName(BodyPart p) {
        switch (regionIndex(p)) {
            case 0: return "cráneo"; case 1: return "columna cervical"; case 2: return "caja torácica";
            case 3: return "abdomen"; case 4: return "pelvis"; case 5: return "húmero/antebrazo";
            case 6: return "carpo/falanges"; case 7: return "fémur"; case 8: return "tibia/peroné";
            default: return "metatarsianos";
        }
    }

    // ═══════════════════════ utilidades ═══════════════════════
    private static ItemStack transparent(String name, List<String> lore) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(name);
        if (lore != null) m.setLore(lore);
        m.setItemModel(TRANSPARENT);
        m.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        it.setItemMeta(m);
        return it;
    }

    private static String integrityBar(double hp) {
        int filled = (int) Math.round(Math.max(0, Math.min(100, hp)) / 10.0);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 10; i++) b.append(i < filled ? '|' : '.');
        return b.toString();
    }
    private static String hpWord(double hp) { return hp > 75 ? "buena" : hp > 50 ? "regular" : hp > 25 ? "mala" : "critica"; }
    private static String painWord(double p) { return p >= 70 ? "INSOPORTABLE" : p >= 40 ? "fuerte" : p >= 15 ? "molesto" : "controlado"; }
    private static TextColor painColor(double p) { return p >= 70 ? T_CRIT : p >= 40 ? T_SERIO : p >= 15 ? T_LEVE : T_INFEC; }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
    private static String truncate(String s, int n) { return s == null ? "" : s.length() <= n ? s : s.substring(0, n); }

    private static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String w : text.split(" ")) {
            if (line.length() > 0 && line.length() + w.length() > width) { out.add(line.toString().trim()); line = new StringBuilder(); }
            line.append(w).append(' ');
        }
        if (line.length() > 0) out.add(line.toString().trim());
        return out;
    }
}
