package com.nemeles.combat.body;

import com.nemeles.core.api.NemelesApi;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ESCUELA DE MEDICINA con examen ANTI-TRAMPA:
 *  1. Pagas el curso -> recibes el MANUAL (libro) y empieza tu tiempo de estudio (min. 5 min reales).
 *  2. /medicina examen -> 5 preguntas ALEATORIAS de un banco, respuestas BARAJADAS, 18s por pregunta.
 *     Las preguntas son sobre las reglas de ESTE servidor (no medicina generica): ni una IA externa
 *     las acierta sin haber leido el manual. Fallar = cooldown de 10 min y a estudiar otra vez.
 *  Banco y manual recargables desde plugins/NemelesCombat/examen_medicina.yml (sin recompilar).
 */
public final class MedExam implements Listener {

    private record Q(String text, String ok, List<String> bad) { }

    private static final class Session {
        int courseTarget;          // 1 basico, 2 avanzado
        List<Q> questions;
        int index, correct;
        List<String> shuffled;     // respuestas de la pregunta actual en orden mostrado
        boolean bedrock;           // examen por SimpleForm tactil (Geyser/Floodgate) en vez del cofre
        BukkitTask timeout;
    }

    private static final String TITLE_PREFIX = ChatColor.DARK_AQUA + "✎ Examen de Medicina ";
    private static final int[] ANSWER_SLOTS = {10, 12, 14, 16};

    private final Plugin plugin;
    private final BodyManager mgr;
    private final NamespacedKey studyB, studyA, examCd;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final List<Q> bankBasico = new ArrayList<>();
    private final List<Q> bankAvanzado = new ArrayList<>();
    private final List<String> manualPages = new ArrayList<>();

    public MedExam(Plugin plugin, BodyManager mgr) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.studyB = new NamespacedKey(plugin, "med_study_b");
        this.studyA = new NamespacedKey(plugin, "med_study_a");
        this.examCd = new NamespacedKey(plugin, "med_exam_cd");
        loadBank();
    }

    /** Carga banco/manual de examen_medicina.yml (si existe); si no, usa los integrados. */
    public void loadBank() {
        bankBasico.clear(); bankAvanzado.clear(); manualPages.clear();
        try {
            File f = new File(plugin.getDataFolder(), "examen_medicina.yml");
            if (f.exists()) {
                var y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
                for (String l : y.getStringList("basico")) parseQ(l, bankBasico);
                for (String l : y.getStringList("avanzado")) parseQ(l, bankAvanzado);
                manualPages.addAll(y.getStringList("manual-pages"));
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[MED] examen_medicina.yml: " + t.getMessage());
        }
        if (bankBasico.isEmpty()) defaultsBasico();
        if (bankAvanzado.isEmpty()) defaultsAvanzado();
        if (manualPages.isEmpty()) defaultsManual();
        plugin.getLogger().info("[MED] Banco de examen: " + bankBasico.size() + " basicas, " + bankAvanzado.size() + " avanzadas.");
    }

    private static void parseQ(String line, List<Q> out) {
        String[] p = line.split("\\|");
        if (p.length >= 5) out.add(new Q(p[0].trim(), p[1].trim(), List.of(p[2].trim(), p[3].trim(), p[4].trim())));
    }

    // ─── estudiar ────────────────────────────────────────────
    public void study(Player p, String which) {
        boolean basico = which.equalsIgnoreCase("basico");
        boolean avanzado = which.equalsIgnoreCase("avanzado");
        if (!basico && !avanzado) {
            p.sendMessage(color("&7/medicina estudiar <basico|avanzado> &8→ pagas, recibes el MANUAL, estudias 5 min y... /medicina examen"));
            return;
        }
        int course = mgr.courseOf(p);
        if (basico && course >= 1) { p.sendMessage(color("&7Ya eres titulado en primeros auxilios.")); return; }
        if (avanzado && course >= 2) { p.sendMessage(color("&7Ya eres médico avanzado.")); return; }
        if (avanzado && course < 1) { p.sendMessage(color("&cPrimero el curso básico.")); return; }
        if (avanzado && mgr.medLevel(p) < 10) { p.sendMessage(color("&cNecesitas Medicina nivel 10 (trata heridos y reanima para practicar).")); return; }

        double cost = basico ? 500 : 2500;
        NemelesApi.economy().withdraw(p.getUniqueId(), com.nemeles.core.api.economy.MoneyType.EFECTIVO,
                        java.math.BigDecimal.valueOf(cost), "medicina:matricula")
                .thenAccept(res -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (res == null || !res.success()) {
                        p.sendMessage(color("&cLa matrícula cuesta $" + (long) cost + " en efectivo."));
                        return;
                    }
                    p.getPersistentDataContainer().set(basico ? studyB : studyA, PersistentDataType.LONG, System.currentTimeMillis());
                    giveManual(p);
                    p.sendMessage(color("&a📚 Matriculado. LEE el manual que te acabo de dar — el examen pregunta sobre ÉL."));
                    p.sendMessage(color("&7Cuando lleves al menos &f5 minutos&7 de estudio: &e/medicina examen " + (basico ? "basico" : "avanzado")));
                }));
    }

    private void giveManual(Player p) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta bm = (BookMeta) book.getItemMeta();
        bm.setTitle("Manual de Primeros Auxilios");
        bm.setAuthor("Cruz de la Bahía");
        for (String page : manualPages) bm.addPage(page);
        book.setItemMeta(bm);
        p.getInventory().addItem(book);
    }

    // ─── examen ──────────────────────────────────────────────
    public void exam(Player p, String which) {
        boolean basico = which.equalsIgnoreCase("basico");
        boolean avanzado = which.equalsIgnoreCase("avanzado");
        if (!basico && !avanzado) { p.sendMessage(color("&7/medicina examen <basico|avanzado>")); return; }
        int course = mgr.courseOf(p);
        if ((basico && course >= 1) || (avanzado && course >= 2)) { p.sendMessage(color("&7Ese título ya lo tienes.")); return; }
        Long cd = p.getPersistentDataContainer().get(examCd, PersistentDataType.LONG);
        if (cd != null && cd > System.currentTimeMillis()) {
            p.sendMessage(color("&cSuspendiste hace poco. Estudia de verdad y vuelve en "
                    + ((cd - System.currentTimeMillis()) / 60000 + 1) + " min."));
            return;
        }
        Long studied = p.getPersistentDataContainer().get(basico ? studyB : studyA, PersistentDataType.LONG);
        if (studied == null) { p.sendMessage(color("&cPrimero matricúlate: /medicina estudiar " + which)); return; }
        long mins = (System.currentTimeMillis() - studied) / 60000;
        if (mins < 5) { p.sendMessage(color("&cLlevas " + mins + " min de estudio. El tribunal exige al menos 5. LEE el manual.")); return; }
        if (sessions.containsKey(p.getUniqueId())) return;

        List<Q> pool = new ArrayList<>(basico ? bankBasico : bankAvanzado);
        if (!basico) pool.addAll(bankBasico);   // el avanzado tambien repasa lo basico
        Collections.shuffle(pool);
        Session s = new Session();
        s.courseTarget = basico ? 1 : 2;
        s.questions = pool.subList(0, Math.min(5, pool.size()));
        s.bedrock = isBedrock(p);   // los jugadores de consola/móvil hacen el examen táctil (SimpleForm)
        sessions.put(p.getUniqueId(), s);
        p.sendMessage(color("&e✎ EXAMEN: 5 preguntas, 18 segundos cada una, máximo 1 fallo. Suerte."));
        showQuestion(p, s);
    }

    private void showQuestion(Player p, Session s) {
        if (s.timeout != null) s.timeout.cancel();
        Q q = s.questions.get(s.index);
        // Mezcla de respuestas: SE GUARDA en la sesión para que el clic/botón mapee al texto correcto.
        s.shuffled = new ArrayList<>(q.bad());
        s.shuffled.add(q.ok());
        Collections.shuffle(s.shuffled);

        if (s.bedrock) {
            // BEDROCK (Geyser/Floodgate): el cofre se ve fatal y el clic es torpe -> menú TÁCTIL nativo.
            // Mismo patrón de reflexión que BedrockBodyForm: sin dependencia de compilación con Cumulus.
            String content = "Pregunta " + (s.index + 1) + " de 5\n\n" + q.text() + "\n\n"
                    + "(18 segundos · máximo 1 fallo)";
            final UUID who = p.getUniqueId();
            boolean sent = sendSimpleForm(p, ChatColor.stripColor(TITLE_PREFIX.trim()), content,
                    s.shuffled,
                    chosen -> Bukkit.getScheduler().runTask(plugin, () -> {
                        Player pl = Bukkit.getPlayer(who);
                        Session cur = sessions.get(who);
                        if (pl == null || cur != s) return;   // cerró el menú o ya expiró: lo gestiona el timeout
                        if (chosen == null || chosen < 0 || chosen >= s.shuffled.size()) return;
                        answer(pl, s, s.shuffled.get(chosen));
                    }));
            if (!sent) {
                // Floodgate/Cumulus no disponible en runtime -> sin regresión: caemos al cofre.
                s.bedrock = false;
                showQuestionChest(p, s);
                return;
            }
        } else {
            showQuestionChest(p, s);
        }

        // 18s por pregunta: sin tiempo para preguntarle a nadie (igual para cofre y SimpleForm).
        UUID id = p.getUniqueId();
        s.timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Session cur = sessions.get(id);
            if (cur == s) fail(p, "&cSe acabó el tiempo. El paciente se te fue... y el título también.");
        }, 18L * 20L);
    }

    /** Render del examen en el cofre 27 (jugadores Java). */
    private void showQuestionChest(Player p, Session s) {
        Q q = s.questions.get(s.index);
        Inventory inv = Bukkit.createInventory(null, 27, TITLE_PREFIX + "(" + (s.index + 1) + "/5)");
        ItemStack qi = new ItemStack(Material.PAPER);
        var qm = qi.getItemMeta();
        qm.setDisplayName(ChatColor.WHITE + "Pregunta " + (s.index + 1));
        qm.setLore(wrap(q.text()));
        qi.setItemMeta(qm);
        inv.setItem(4, qi);
        for (int i = 0; i < ANSWER_SLOTS.length && i < s.shuffled.size(); i++) {
            ItemStack a = new ItemStack(Material.BOOK);
            var am = a.getItemMeta();
            am.setDisplayName(ChatColor.YELLOW + String.valueOf((char) ('A' + i)) + ") " + ChatColor.WHITE + s.shuffled.get(i));
            a.setItemMeta(am);
            inv.setItem(ANSWER_SLOTS[i], a);
        }
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = e.getView().getTitle();
        if (title == null || !title.startsWith(TITLE_PREFIX)) return;
        e.setCancelled(true);
        Session s = sessions.get(p.getUniqueId());
        if (s == null || s.bedrock) return;   // el examen táctil no usa este inventario
        int idx = -1;
        for (int i = 0; i < ANSWER_SLOTS.length; i++) if (e.getRawSlot() == ANSWER_SLOTS[i]) idx = i;
        if (idx < 0 || idx >= s.shuffled.size()) return;
        answer(p, s, s.shuffled.get(idx));
    }

    /** Puntúa la respuesta elegida (texto) y avanza el examen. Común a cofre (Java) y SimpleForm (Bedrock). */
    private void answer(Player p, Session s, String chosenText) {
        Q q = s.questions.get(s.index);
        boolean ok = chosenText.equals(q.ok());
        if (ok) {
            s.correct++;
            p.sendMessage(color("&a✔ Correcto."));
        } else {
            p.sendMessage(color("&c✘ Incorrecto. (era: " + q.ok() + ")"));
        }
        s.index++;
        if (s.index - s.correct >= 2) { fail(p, "&cDemasiados fallos. El tribunal te invita amablemente a volver a estudiar."); return; }
        if (s.index >= s.questions.size()) {
            if (s.timeout != null) s.timeout.cancel();
            sessions.remove(p.getUniqueId());
            p.closeInventory();
            if (s.correct >= 4) {
                mgr.setCourse(p, s.courseTarget);
                p.sendMessage(color("&a&l🎓 APROBADO (" + s.correct + "/5). " + (s.courseTarget == 1
                        ? "Titulado en PRIMEROS AUXILIOS: ya puedes vendar y entablillar."
                        : "MÉDICO AVANZADO: extracción de balas e infecciones desbloqueadas.")));
                try { p.playSound(p.getLocation(), "ui.toast.challenge_complete", 1f, 1f); } catch (Throwable ignored) { }
            } else {
                fail(p, "&cNota: " + s.correct + "/5. Insuficiente (mínimo 4). A estudiar.");
            }
        } else {
            showQuestion(p, s);
        }
    }

    private void fail(Player p, String msg) {
        Session s = sessions.remove(p.getUniqueId());
        if (s != null && s.timeout != null) s.timeout.cancel();
        p.closeInventory();
        p.sendMessage(color(msg));
        p.getPersistentDataContainer().set(examCd, PersistentDataType.LONG, System.currentTimeMillis() + 10 * 60_000L);
        try { p.playSound(p.getLocation(), "block.note_block.bass", 1f, 0.5f); } catch (Throwable ignored) { }
    }

    // ─── contenidos integrados (los sobreescribe examen_medicina.yml) ──
    private void defaultsBasico() {
        parseQ("¿Con qué cortas un sangrado leve?|Una venda|Una férula|Antibióticos|Pinzas", bankBasico);
        parseQ("¿Qué corta un sangrado GRAVE?|Venda estéril o torniquete|Una venda normal|Pomada|Esperar", bankBasico);
        parseQ("El torniquete solo se aplica en...|Brazos y piernas|La cabeza|El torso|Cualquier parte", bankBasico);
        parseQ("¿Qué tratamiento pide una fractura NORMAL?|Férula + analgésicos|Solo venda|Reposo y ya|Antibióticos", bankBasico);
        parseQ("Una FISURA (fractura leve) se trata con...|Férula|Sutura|Torniquete|Pomada", bankBasico);
        parseQ("Una caída BAJA suele causar...|Esguince|Fractura de muslo|Infección|Bala alojada", bankBasico);
        parseQ("Caer OTRA VEZ sobre una pierna tocada...|EMPEORA la lesión (escala)|No pasa nada nuevo|La cura|Solo duele", bankBasico);
        parseQ("¿Qué pasa si NO tratas un sangrado?|Pierdes vida cada 10s|Nada grave|Solo duele al correr|Se cura al comer", bankBasico);
        parseQ("Una parte SIN vendar...|NO regenera nunca|Cura sola despacio|Cura al dormir|Cura al comer", bankBasico);
        parseQ("Piernas rotas significan...|Cojera y sin esprintar|Visión borrosa|Puntería temblorosa|Menos hambre", bankBasico);
        parseQ("¿Qué cura una QUEMADURA?|Pomada|Pinzas|Una férula|Agua", bankBasico);
        parseQ("Un esguince se cura con...|Venda y reposo|Cirugía|Antibióticos|Amputación", bankBasico);
        parseQ("¿Para qué sirve el DESINFECTANTE?|Evita que una herida sucia se infecte|Cura fracturas|Quita el dolor|Cierra cortes", bankBasico);
        parseQ("¿Qué hacen los ANALGÉSICOS?|Alivian el dolor 2 min (no curan)|Curan la parte|Cortan sangrados|Quitan infecciones", bankBasico);
        parseQ("¿Cuánto cuesta el curso básico?|$500|$50|$2500|Es gratis", bankBasico);
        parseQ("Brazos heridos provocan...|Puntería temblorosa|Cojera|Ceguera|Hambre", bankBasico);
    }

    private void defaultsAvanzado() {
        parseQ("¿Con qué extraes una bala SUPERFICIAL?|Pinzas|Venda|Férula|La mano", bankAvanzado);
        parseQ("¿Y una bala PROFUNDA?|Bisturí + sutura (otro médico)|Pinzas y listo|Torniquete|Antibióticos", bankAvanzado);
        parseQ("Tras extraer una bala profunda, la incisión...|Sangra: hay que vendarla|Queda perfecta|Se infecta siempre|Se sutura sola", bankAvanzado);
        parseQ("Un corte PROFUNDO pide...|Kit de sutura|Solo venda|Pomada|Hielo", bankAvanzado);
        parseQ("¿Qué trata una INFECCIÓN declarada?|Antibióticos|Venda|Vendar dos veces|Esperar", bankAvanzado);
        parseQ("Cada parte infectada te quita...|2 corazones máximos|1 nivel de comida|Toda la vida|Nada", bankAvanzado);
        parseQ("Una fractura ABIERTA...|Solo la trata OTRO médico avanzado|La arreglas tú con férula|Cura con reposo|No existe", bankAvanzado);
        parseQ("Las heridas que se infectan son...|Sucias: bala, sangrado, quemadura, tajo|Solo las de caída|Solo mordiscos|Ninguna", bankAvanzado);
        parseQ("El curso avanzado requiere...|Medicina nivel 10 y $2500|Solo pagar|Ser policía|Nivel 50 de minero", bankAvanzado);
        parseQ("Si la cirugía sale mal, el paciente...|Pierde algo de vida|Muere siempre|Queda ciego|Nada", bankAvanzado);
        parseQ("¿Qué priorizas en un paciente que sangra Y tiene fractura?|El sangrado|La fractura|La quemadura|Darle comida", bankAvanzado);
        parseQ("A un DERRIBADO se le pone sangre para...|Estabilizarlo y poder trasladarlo|Revivirlo al instante|Quitarle el dolor|Nada", bankAvanzado);
    }

    private void defaultsManual() {
        manualPages.add("§lMANUAL DE PRIMEROS AUXILIOS§r\nCruz de la Bahía\n\n1. SANGRADOS: drenan vida cada 10s. Leve→VENDA. Moderado→venda (con curso). GRAVE→VENDA ESTÉRIL o TORNIQUETE (solo brazos/piernas). Primero el sangrado, SIEMPRE.");
        manualPages.add("2. HUESOS: fisura→FÉRULA. Fractura→FÉRULA + ANALGÉSICOS. Fractura ABIERTA→solo OTRO médico avanzado (sutura+férula). OJO: castigar una pierna tocada ESCALA la lesión. Esguinces: venda y reposo.");
        manualPages.add("3. BALAS: superficial→PINZAS. PROFUNDA→BISTURÍ + SUTURA, y NUNCA a ti mismo. La incisión sangra: véndala. Cortes profundos→KIT DE SUTURA.");
        manualPages.add("4. INFECCIONES: heridas sucias (bala, sangrado, tajo, quemadura) se infectan. Herida sucia→DESINFECTANTE a tiempo. Infección declarada→ANTIBIÓTICOS (avanzado). Cada parte infectada = -2 corazones máximos.");
        manualPages.add("5. DOLOR Y SECUELAS: el dolor se acumula; ANALGÉSICOS = 2 min de tregua (andas mejor pese a la lesión). Piernas rotas = cojera. Brazos = puntería temblorosa. Quemaduras→POMADA.");
        manualPages.add("6. REGLA DE ORO: una parte SIN VENDAR NO REGENERA. Vendada + comida = cura lenta. DERRIBADOS: bolsa de SANGRE = +2 min y puede arrastrarse (traslado). Venda antes de preguntar el nombre.");
    }

    private static List<String> wrap(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String w : text.split(" ")) {
            if (line.length() + w.length() > 32) { out.add(ChatColor.GRAY + line.toString()); line = new StringBuilder(); }
            line.append(w).append(' ');
        }
        if (!line.isEmpty()) out.add(ChatColor.GRAY + line.toString().trim());
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  BEDROCK: SimpleForm de Cumulus por REFLEXIÓN (sin dependencia de compilación),
    //  enviado vía FloodgateApi. Idéntico patrón a BedrockBodyForm/MarketManager: si
    //  Floodgate/Cumulus no están en runtime, isBedrock=false y sendSimpleForm=false,
    //  y el examen cae al cofre 27 (sin regresión para Java ni si falta el plugin).
    // ─────────────────────────────────────────────────────────────────────────────

    /** True si el jugador entró por Bedrock (Floodgate). Reflexión-safe: false si Floodgate no está. */
    private static boolean isBedrock(Player player) {
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled("floodgate")) return false;
            Class<?> apiCls = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = apiCls.getMethod("getInstance").invoke(null);
            return (boolean) apiCls.getMethod("isFloodgatePlayer", UUID.class).invoke(api, player.getUniqueId());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Envía un SimpleForm (un botón por respuesta). Devuelve false si Cumulus/Floodgate no está. */
    private static boolean sendSimpleForm(Player p, String title, String content,
                                          List<String> buttons, java.util.function.Consumer<Integer> onButton) {
        try {
            Class<?> simpleFormCls = Class.forName("org.geysermc.cumulus.form.SimpleForm");
            Class<?> builderCls = Class.forName("org.geysermc.cumulus.form.SimpleForm$Builder");
            Object builder = simpleFormCls.getMethod("builder").invoke(null);
            var titleM = builderCls.getMethod("title", String.class);
            var contentM = builderCls.getMethod("content", String.class);
            var buttonM = builderCls.getMethod("button", String.class);
            var buildM = builderCls.getMethod("build");
            var handlerM = builderCls.getMethod("validResultHandler", java.util.function.Consumer.class);

            titleM.invoke(builder, title);
            contentM.invoke(builder, content);
            for (int i = 0; i < buttons.size(); i++) {
                // Letra A) B)... para que la respuesta se lea igual que en el cofre.
                String label = (char) ('A' + i) + ") " + ChatColor.stripColor(buttons.get(i));
                buttonM.invoke(builder, label);
            }

            java.util.function.Consumer<Object> handler = resp -> {
                try {
                    if (resp == null) return;   // cerró el menú: lo gestiona el timeout de 18s
                    int id = (int) resp.getClass().getMethod("clickedButtonId").invoke(resp);
                    onButton.accept(id);
                } catch (Throwable ignored) { }
            };
            handlerM.invoke(builder, handler);
            Object form = buildM.invoke(builder);

            Class<?> floodgateApiCls = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = floodgateApiCls.getMethod("getInstance").invoke(null);
            Class<?> formIface = Class.forName("org.geysermc.cumulus.form.Form");
            floodgateApiCls.getMethod("sendForm", UUID.class, formIface).invoke(api, p.getUniqueId(), form);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
}
