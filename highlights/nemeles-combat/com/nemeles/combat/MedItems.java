package com.nemeles.combat;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * EQUIPO MEDICO custom (estilo Project Zomboid / Tarkov): items con identidad por PDC
 * (med_item) + CustomModelData para textura propia en Java (Bedrock ve el item base con nombre).
 * Cada herida tiene SU material: el papel ya no cura — ahora hay vendas, bisturi, suturas...
 * Se venden en la FARMACIA (nemeles-market) y algunos se craftean.
 */
public final class MedItems {

    public static final class Def {
        public final String id, display;
        public final Material base;
        public final int cmd;
        public final boolean tool;       // true = no se consume (bisturi, pinzas)
        public final List<String> lore;
        Def(String id, String display, Material base, int cmd, boolean tool, String... lore) {
            this.id = id; this.display = display; this.base = base; this.cmd = cmd; this.tool = tool;
            this.lore = List.of(lore);
        }
    }

    public static final String VENDA = "venda";
    public static final String VENDA_ESTERIL = "venda_esteril";
    public static final String TORNIQUETE = "torniquete";
    public static final String FERULA = "ferula";
    public static final String BISTURI = "bisturi";
    public static final String PINZAS = "pinzas";
    public static final String SUTURA = "sutura";
    public static final String ANTIBIOTICO = "antibiotico";
    public static final String ANALGESICO = "analgesico";
    public static final String DESINFECTANTE = "desinfectante";
    public static final String POMADA = "pomada";
    public static final String SANGRE = "sangre";
    public static final String MEDKIT = "medkit";

    private static final Map<String, Def> DEFS = new LinkedHashMap<>();
    static {
        reg(new Def(VENDA, "§fVenda", Material.PAPER, 4341, false,
                "§7Corta sangrados leves y moderados.",
                "§7Una parte VENDADA es la única que regenera.",
                "§8Farmacia · craft: 2 papel + 1 hilo"));
        reg(new Def(VENDA_ESTERIL, "§bVenda estéril", Material.PAPER, 4342, false,
                "§7Corta CUALQUIER sangrado (incluso grave)",
                "§7y desinfecta la herida al cubrirla.",
                "§8Farmacia"));
        reg(new Def(TORNIQUETE, "§cTorniquete", Material.LEAD, 4352, false,
                "§7Detiene en seco un sangrado GRAVE en una",
                "§7extremidad. Brutal pero eficaz: duele y daña.",
                "§8Cualquiera puede usarlo (sin curso)"));
        reg(new Def(FERULA, "§eFérula", Material.STICK, 4343, false,
                "§7Inmoviliza fisuras y fracturas.",
                "§7Las fracturas graves piden también analgésico.",
                "§8Farmacia · craft: 2 palos + 1 hilo"));
        reg(new Def(BISTURI, "§fBisturí quirúrgico", Material.SHEARS, 4344, true,
                "§7Cirugía: extraer balas PROFUNDAS y abrir",
                "§7lo que haya que abrir. No se gasta.",
                "§8Curso avanzado · Farmacia"));
        reg(new Def(PINZAS, "§7Pinzas", Material.SHEARS, 4345, true,
                "§7Extraen balas superficiales y cristales.",
                "§7No se gastan.",
                "§8Curso avanzado · Farmacia"));
        reg(new Def(SUTURA, "§dKit de sutura", Material.STRING, 4346, false,
                "§7Aguja e hilo: cierra cortes PROFUNDOS",
                "§7y heridas de cirugía.",
                "§8Curso avanzado · Farmacia"));
        reg(new Def(ANTIBIOTICO, "§aAntibióticos", Material.HONEY_BOTTLE, 4347, false,
                "§7Mata una INFECCIÓN declarada.",
                "§7Caros y con receta... o mercado negro.",
                "§8Curso avanzado · Farmacia"));
        reg(new Def(ANALGESICO, "§6Analgésicos", Material.SUGAR, 4348, false,
                "§7Clic derecho: alivia el DOLOR 2 minutos",
                "§7(caminas mejor pese a las lesiones).",
                "§8Sin curso · Farmacia"));
        reg(new Def(DESINFECTANTE, "§bDesinfectante", Material.GLASS_BOTTLE, 4349, false,
                "§7Limpia una herida sucia ANTES de que",
                "§7se infecte (baja el riesgo a cero).",
                "§8Sin curso · Farmacia"));
        reg(new Def(POMADA, "§6Pomada para quemaduras", Material.HONEYCOMB, 4350, false,
                "§7Calma y regenera la piel quemada.",
                "§8Farmacia"));
        reg(new Def(SANGRE, "§4Bolsa de sangre", Material.RED_DYE, 4351, false,
                "§7Transfusión a un DERRIBADO: +2 min de vida",
                "§7y puede arrastrarse para ser trasladado.",
                "§8Hospital / Farmacia"));
        // Botiquín: la HERRAMIENTA para examinar (abrir la ficha). Reusa la textura medkit (CMD 4320,
        // ya mapeada en Geyser -> se ve en Bedrock). También sirve de botiquín de reanimación.
        reg(new Def(MEDKIT, "§aBotiquín de primeros auxilios", Material.GLISTERING_MELON_SLICE, 4320, true,
                "§7Necesario para EXAMINAR heridas (/cuerpo o",
                "§7clic derecho a un herido).",
                "§7Sin estudios solo pondrás curitas y vendas mal puestas.",
                "§8Farmacia · también vale para reanimar"));
    }
    private static void reg(Def d) { DEFS.put(d.id, d); }

    private static NamespacedKey KEY;

    private MedItems() { }

    public static void init(Plugin plugin) { KEY = new NamespacedKey(plugin, "med_item"); }

    public static Map<String, Def> defs() { return DEFS; }

    public static ItemStack create(String id, int amount) {
        Def d = DEFS.get(id == null ? "" : id.toLowerCase(Locale.ROOT));
        if (d == null || KEY == null) return null;
        ItemStack it = new ItemStack(d.base, Math.max(1, amount));
        ItemMeta m = it.getItemMeta();
        if (m == null) return it;
        m.setDisplayName(d.display);
        List<String> lore = new ArrayList<>(d.lore);
        m.setLore(lore);
        if (d.cmd > 0) m.setCustomModelData(d.cmd);
        m.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, d.id);
        it.setItemMeta(m);
        return it;
    }

    /** id del item medico, o null si no lo es. */
    public static String idOf(ItemStack it) {
        if (it == null || KEY == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
    }

    public static boolean is(ItemStack it, String id) { return id != null && id.equals(idOf(it)); }

    public static int firstSlot(Player p, String id) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (is(inv.getItem(i), id)) return i;
        }
        return -1;
    }

    public static boolean has(Player p, String id) { return firstSlot(p, id) >= 0; }

    /**
     * Consume una unidad del item (o solo comprueba presencia si es herramienta).
     * saveChance = prob. de NO gastar material (manos expertas, por nivel de medicina).
     */
    public static boolean consume(Player p, String id, double saveChance) {
        int slot = firstSlot(p, id);
        if (slot < 0) return false;
        Def d = DEFS.get(id);
        if (d != null && d.tool) return true;                      // bisturi/pinzas no se gastan
        if (saveChance > 0 && ThreadLocalRandom.current().nextDouble() < saveChance) {
            p.sendMessage(ChatColor.GRAY + "(manos expertas: no gastaste " + plain(id) + ")");
            return true;
        }
        ItemStack it = p.getInventory().getItem(slot);
        it.setAmount(it.getAmount() - 1);
        return true;
    }

    /** Nombre sin color para mensajes. */
    public static String plain(String id) {
        Def d = DEFS.get(id);
        return d == null ? id : ChatColor.stripColor(ChatColor.translateAlternateColorCodes('§', d.display)).toLowerCase(Locale.ROOT);
    }

    public static String display(String id) {
        Def d = DEFS.get(id);
        return d == null ? id : d.display;
    }
}
