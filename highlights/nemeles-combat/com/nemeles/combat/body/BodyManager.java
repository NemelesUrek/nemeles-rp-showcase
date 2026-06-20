package com.nemeles.combat.body;

import com.nemeles.combat.MedItems;
import com.nemeles.core.api.NemelesApi;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sistema medico por PARTES v2 (estilo Project Zomboid / PopLife):
 *  - GRAVEDAD acumulativa: caer dos veces sobre la misma pierna EMPEORA la lesion (fisura ->
 *    fractura -> fractura abierta), no repite el mismo dano.
 *  - SIN regeneracion natural: solo una parte VENDADA regenera. Lo grave pide medico.
 *  - DOLOR: se acumula con cada herida, se alivia con analgesicos (2 min de tregua).
 *  - Mensajes con VARIANTES y anti-spam (cada aviso respira; no metralleta de chat).
 *  - Cada herida tiene SU material (MedItems): venda, venda esteril, torniquete, ferula,
 *    bisturi, pinzas, sutura, antibiotico, desinfectante, pomada.
 *  - Lo CRITICO (fractura abierta, bala profunda, infeccion avanzada) NO te lo puedes
 *    tratar tu mismo: necesitas OTRO jugador con formacion.
 */
public final class BodyManager {

    /** Estado vivo de una parte del cuerpo. */
    public static final class PartState {
        public double hp = 100;
        public double bleeding;          // 0=no | <0.35 leve | <0.8 moderado | >=0.8 GRAVE
        public int fractura;             // 0 | 1 fisura | 2 fractura | 3 fractura ABIERTA
        public int esguince;             // 0 | 1 leve | 2 fuerte
        public int quemadura;            // 0 | 1 superficial | 2 profunda
        public int contusion;            // 0 | 1 rozadura/raspon | 2 moraton | 3 contusion fuerte (golpe/puno)
        public boolean corteProfundo;    // laceracion que pide SUTURA antes de vendar (puñalada honda)
        public boolean balaDentro;
        public boolean balaProfunda;     // pide BISTURI (cirugia real), no pinzas
        public boolean infectado;
        public boolean vendada;          // unica via de regeneracion
        public long vendadaAt;           // cuando se vendo: la venda se ENSUCIA con el tiempo (PZ)
        public double infectionRisk;     // 0..1 acumulado por heridas sucias
        public boolean any() {
            return hp < 100 || bleeding > 0 || fractura > 0 || esguince > 0 || quemadura > 0
                    || infectado || balaDentro || corteProfundo || contusion > 0;
        }
    }

    public enum WoundType { BALA, CORTE, CONTUSION, FRACTURA, ESGUINCE, QUEMADURA }

    private final Plugin plugin;
    private final NamespacedKey courseKey;
    private final MedMessages msgs;     // mensajes data-driven (mensajes_heridas.yml, equipo de IAs)
    private final Map<UUID, EnumMap<BodyPart, PartState>> bodies = new ConcurrentHashMap<>();
    private final Map<UUID, Double> dolor = new ConcurrentHashMap<>();          // 0..100
    private final Map<UUID, Long> painkillerUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> msgCd = new ConcurrentHashMap<>(); // anti-spam

    public BodyManager(Plugin plugin) {
        this.plugin = plugin;
        this.courseKey = new NamespacedKey(plugin, "med_course");
        this.msgs = new MedMessages(plugin);
        this.minigameEnabled = plugin.getConfig().getBoolean("treat.minigame", true);
        this.graceTicks = Math.max(4, plugin.getConfig().getInt("treat.grace-seconds", 3) * 4);
    }

    public MedMessages messages() { return msgs; }

    // ─── MINIJUEGO de curacion canalizada (solo lo serio) ────────────────
    // 4 minijuegos INMERSIVOS, uno por tratamiento (no al azar), con MARGEN DE GRACIA:
    //  BALA -> PULSO (mira quieta) · SUTURA -> PUNTADAS (toggle de agacharse a ritmo)
    //  FRACTURA -> EL TIRON (tension + toggle en la ventana) · INFECCION -> LIMPIEZA (frotar suave)
    // Tick de 0.25s (lo programa el plugin a 5L). Crossplay: sneak + mira funcionan via Geyser.
    private final boolean minigameEnabled;
    private final int graceTicks;        // margen para recolocarte (no cancela al instante)
    private static final class Channel {
        UUID medic, patient; BodyPart part; String kind;
        int ticks, needed; double pFail;
        int graceLeft;                   // ticks de margen restantes (se restaura al cumplir)
        int mistakes;                    // errores del minijuego: suben el fallo final
        int stepsDone, stepsNeeded;      // puntadas / tirones
        int windowTicks, nextWindowIn;   // ventana de accion abierta / cuenta atras a la proxima
        int tension;                     // FRACTURA: acumulador del tiron
        boolean lastSneak, hasLook;
        float lastYaw, lastPitch;
        org.bukkit.Location startBlock; org.bukkit.boss.BossBar bar;
    }
    private final Map<UUID, Channel> channels = new ConcurrentHashMap<>();          // por medico
    private final java.util.Set<String> channelDone = ConcurrentHashMap.newKeySet();

    private static String channelKey(UUID medic, UUID patient, BodyPart part) {
        return medic + ":" + patient + ":" + part.name();
    }

    /** ¿Es un tratamiento SERIO (canalizable) y aplicable ahora? Devuelve la clave o null (leve/no-tratable). */
    private String seriousTreatment(PartState s, int course, boolean self) {
        if (s.bleeding > 0) return null;                         // sangrado: rapido (venda/torniquete)
        if (s.balaDentro) {
            if (course < 2) return null;
            if (s.balaProfunda && self) return null;
            return "BALA";
        }
        if (s.corteProfundo) { return course < 2 ? null : "SUTURA"; }
        if (s.fractura > 0) {
            if (course < 1) return null;
            if (s.fractura >= 3 && (self || course < 2)) return null;
            return "FRACTURA";
        }
        if (s.infectado) {
            if (course < 2) return null;
            if (s.hp <= 20 && self) return null;
            return "INFECCION";
        }
        return null;   // quemadura, esguince, desinfectar, vendar = instantaneo
    }

    private int baseSecondsFor(String kind) {
        return switch (kind) {
            case "BALA" -> 12; case "SUTURA" -> 9; case "FRACTURA" -> 8; case "INFECCION" -> 7; default -> 6;
        };
    }

    private double baseFailFor(String kind) {
        return switch (kind) {
            case "BALA" -> 0.45; case "FRACTURA" -> 0.35; case "SUTURA" -> 0.30; case "INFECCION" -> 0.25; default -> 0.18;
        };
    }

    /** Item que FALTA para el tratamiento canalizado (o null si tiene todo). Espeja los requisitos de treat(). */
    private String missingMaterial(Player medic, PartState s, String kind) {
        switch (kind) {
            case "BALA":
                if (s.balaProfunda) {
                    if (!MedItems.has(medic, MedItems.BISTURI)) return MedItems.BISTURI;
                    if (!MedItems.has(medic, MedItems.SUTURA)) return MedItems.SUTURA;
                } else if (!MedItems.has(medic, MedItems.PINZAS)) return MedItems.PINZAS;
                return null;
            case "SUTURA":
                return MedItems.has(medic, MedItems.SUTURA) ? null : MedItems.SUTURA;
            case "FRACTURA":
                if (s.fractura >= 3) {
                    if (!MedItems.has(medic, MedItems.SUTURA)) return MedItems.SUTURA;
                    if (!MedItems.has(medic, MedItems.FERULA)) return MedItems.FERULA;
                } else if (s.fractura == 2) {
                    if (!MedItems.has(medic, MedItems.ANALGESICO)) return MedItems.ANALGESICO;
                    if (!MedItems.has(medic, MedItems.FERULA)) return MedItems.FERULA;
                } else if (!MedItems.has(medic, MedItems.FERULA)) return MedItems.FERULA;
                return null;
            case "INFECCION":
                return MedItems.has(medic, MedItems.ANTIBIOTICO) ? null : MedItems.ANTIBIOTICO;
            default:
                return null;
        }
    }

    /** Inicia el canal: minijuego según el tratamiento; mas nivel = mas rapido y menos fallo. Cierra la GUI. */
    private void beginChannel(Player medic, Player patient, BodyPart part, String kind) {
        UUID mid = medic.getUniqueId();
        if (channels.containsKey(mid)) { medic.sendMessage(color("&7Ya estás tratando algo. Termina primero.")); return; }
        boolean self = mid.equals(patient.getUniqueId());
        int level = medLevel(medic);
        int base = baseSecondsFor(kind);
        int floor = Math.max(3, base / 2);
        int neededSecs = Math.max(floor, base - (int) Math.round((base - floor) * Math.min(100, level) / 100.0));
        if (self) neededSecs = (int) Math.round(neededSecs * 1.4);
        if (part.isVital()) neededSecs = (int) Math.round(neededSecs * 1.25);   // cabeza/cuello/torso: mas fino
        double pFail = baseFailFor(kind) - level * 0.009 - (courseOf(medic) - 1) * 0.05
                + (self ? 0.10 : 0) + painOf(patient.getUniqueId()) / 100.0 * 0.05;
        pFail = Math.max(0.03, Math.min(0.85, pFail));

        Channel c = new Channel();
        c.medic = mid; c.patient = patient.getUniqueId(); c.part = part; c.kind = kind;
        c.needed = neededSecs * 4;   // ticks de 0.25s
        c.pFail = pFail;
        c.graceLeft = graceTicks;
        c.lastSneak = medic.isSneaking();
        c.startBlock = medic.getLocation().getBlock().getLocation();
        // pasos segun minijuego (las partes vitales piden un paso extra de cuidado)
        switch (kind) {
            case "SUTURA" -> c.stepsNeeded = (part.isVital() ? 5 : 4);
            case "FRACTURA" -> c.stepsNeeded = 1;
            default -> c.stepsNeeded = 0;
        }
        c.nextWindowIn = 8;   // primera ventana de puntada a los 2s
        c.bar = Bukkit.createBossBar(color("&eTratando " + part.display + "..."),
                org.bukkit.boss.BarColor.YELLOW, org.bukkit.boss.BarStyle.SOLID);
        c.bar.setProgress(0);
        c.bar.addPlayer(medic);
        channels.put(mid, c);
        medic.closeInventory();
        medic.sendMessage(color(switch (kind) {
            case "BALA" -> "&e✚ EXTRACCIÓN: busca el plomo en " + part.display + ". Agáchate, quieto, y mantén el &fPULSO&e: NO muevas la mira.";
            case "SUTURA" -> "&e✚ SUTURA en " + part.display + ": quieto y atento — cuando salte &f¡PUNTADA!&e, agáchate (y suelta). Una a una.";
            case "FRACTURA" -> "&e✚ RECOLOCAR " + part.display + ": quieto, sujeta firme... y cuando grite &f¡AHORA!&e, agáchate DE GOLPE para el tirón.";
            case "INFECCION" -> "&e✚ LIMPIEZA del foco en " + part.display + ": agáchate y frota con la mira en &fcirculitos suaves&e, sin parar ni pasarte.";
            default -> "&eEmpiezas a trabajar sobre " + part.display + ". Quédate agachado y quieto.";
        }));
        medic.sendMessage(color("&8(si te mueves o te levantas tienes unos segundos de margen para volver — un golpe sí corta)"));
        try { medic.playSound(medic.getLocation(), "block.brewing_stand.brew", 0.6f, 1f); } catch (Throwable ignored) { }
    }

    /** Tick de 0.25s: avanza los minijuegos de curacion (lo programa el plugin a 5L). */
    public void treatScan() {
        for (Channel c : new java.util.ArrayList<>(channels.values())) {
            Player medic = Bukkit.getPlayer(c.medic);
            Player patient = Bukkit.getPlayer(c.patient);
            if (medic == null || patient == null) { channels.remove(c.medic); if (c.bar != null) c.bar.removeAll(); continue; }
            boolean sneak = medic.isSneaking();
            boolean pressed = sneak && !c.lastSneak;   // solo el AGACHARSE cuenta como accion (soltar se ignora)
            c.lastSneak = sneak;

            // ── POSTURA con MARGEN DE GRACIA: salirte no cancela al instante, te avisa y te da
            // unos segundos para volver (la barra se PAUSA). Solo un golpe corta en seco.
            boolean moved = c.startBlock != null && (medic.getLocation().getBlockX() != c.startBlock.getBlockX()
                    || medic.getLocation().getBlockZ() != c.startBlock.getBlockZ()
                    || Math.abs(medic.getLocation().getBlockY() - c.startBlock.getBlockY()) > 1);
            boolean far = !c.medic.equals(c.patient)
                    && (!patient.getWorld().equals(medic.getWorld())
                        || patient.getLocation().distanceSquared(medic.getLocation()) > 9.0);
            // agacharse es REQUISITO en pulso/limpieza; en puntadas/tiron el sneak es el INPUT
            boolean needSneak = c.kind.equals("BALA") || c.kind.equals("INFECCION");
            boolean okPosture = !moved && !far && (!needSneak || sneak);
            if (!okPosture) {
                if (--c.graceLeft <= 0) { interruptChannel(c, medic, "MOVER"); continue; }
                if (c.bar != null) c.bar.setColor(org.bukkit.boss.BarColor.RED);
                medic.sendActionBar(net.kyori.adventure.text.Component.text(color(
                        "&c¡Vuelve! " + (far ? "(acércate al paciente)" : moved ? "(quédate en el sitio)" : "(agáchate)")
                        + " &7" + ((c.graceLeft + 3) / 4) + "s de margen")));
                continue;   // pausa: ni avanza ni retrocede
            }
            c.graceLeft = graceTicks;
            if (c.bar != null) c.bar.setColor(org.bukkit.boss.BarColor.YELLOW);

            // ── MINIJUEGO según el tratamiento ──
            switch (c.kind) {
                case "BALA" -> {        // PULSO: la mira QUIETA; temblar retrocede
                    double d = lookDelta(c, medic);
                    if (d <= 1.6) { c.ticks++; barAndPct(c, medic, "&ePulso firme… extrayendo de " + c.part.display); }
                    else if (d <= 6.0) { medic.sendActionBar(net.kyori.adventure.text.Component.text(color("&6Se te va la mano… respira (no muevas la mira)"))); }
                    else {
                        c.mistakes++; c.ticks = Math.max(0, c.ticks - 4);
                        addPain(c.patient, 2);
                        medic.sendActionBar(net.kyori.adventure.text.Component.text(color("&c¡El pulso se te dispara! La herida se queja.")));
                        try { medic.playSound(medic.getLocation(), "entity.player.hurt", 0.5f, 1.4f); } catch (Throwable ignored) { }
                    }
                }
                case "INFECCION" -> {   // LIMPIEZA: frotar SUAVE y constante (ni quieto ni brusco)
                    double d = lookDelta(c, medic);
                    if (d >= 1.2 && d <= 9.0) { c.ticks++; barAndPct(c, medic, "&eFrotando el foco de " + c.part.display + "… así, suave"); }
                    else if (d < 1.2) { medic.sendActionBar(net.kyori.adventure.text.Component.text(color("&6No estás limpiando: mueve la mira en circulitos suaves"))); }
                    else {
                        c.mistakes++; c.ticks = Math.max(0, c.ticks - 3);
                        addPain(c.patient, 2);
                        medic.sendActionBar(net.kyori.adventure.text.Component.text(color("&c¡Demasiado fuerte! Así lo extiendes.")));
                    }
                }
                case "SUTURA" -> {      // PUNTADAS: agacharse (press) cuando salta la ventana
                    if (c.windowTicks > 0) {
                        if (pressed) {
                            c.stepsDone++; c.windowTicks = 0; c.nextWindowIn = 6 + ThreadLocalRandom.current().nextInt(6);
                            medic.sendActionBar(net.kyori.adventure.text.Component.text(color("&a✚ Puntada firme (" + c.stepsDone + "/" + c.stepsNeeded + ")")));
                            try { medic.playSound(medic.getLocation(), "entity.experience_orb.pickup", 0.7f, 1.6f); } catch (Throwable ignored) { }
                        } else if (--c.windowTicks <= 0) {
                            c.mistakes++; c.nextWindowIn = 8;
                            addPain(c.patient, 1);
                            medic.sendActionBar(net.kyori.adventure.text.Component.text(color("&cSe te fue la aguja: puntada perdida.")));
                        } else {
                            medic.sendActionBar(net.kyori.adventure.text.Component.text(color("&e&l¡PUNTADA! &eda el toque de agacharte &7(" + ((c.windowTicks + 3) / 4.0) + "s)")));
                        }
                    } else if (--c.nextWindowIn <= 0) {
                        c.windowTicks = 8;   // 2s de ventana
                        try { medic.playSound(medic.getLocation(), "block.note_block.pling", 1f, 1.8f); } catch (Throwable ignored) { }
                    } else {
                        medic.sendActionBar(net.kyori.adventure.text.Component.text(color("&7Prepara la aguja… espera la señal (" + c.stepsDone + "/" + c.stepsNeeded + ")")));
                    }
                    if (c.bar != null) c.bar.setProgress(Math.min(1.0, c.stepsDone / (double) c.stepsNeeded));
                }
                case "FRACTURA" -> {    // EL TIRON: acumula tension quieto; agacharse SOLO en la ventana
                    int tensionNeeded = c.needed / 2;
                    if (c.windowTicks > 0) {
                        if (pressed) {
                            c.stepsDone++; c.windowTicks = 0;
                            try { medic.playSound(medic.getLocation(), "entity.item.break", 1f, 0.7f); } catch (Throwable ignored) { }
                        } else if (--c.windowTicks <= 0) {
                            c.tension = tensionNeeded / 2;   // ventana perdida: pierdes media tension
                            medic.sendActionBar(net.kyori.adventure.text.Component.text(color("&cTarde. Recupera la sujeción…")));
                        } else {
                            medic.sendActionBar(net.kyori.adventure.text.Component.text(color("&c&l¡AHORA! ¡EL TIRÓN! &7(agáchate de golpe)")));
                        }
                    } else {
                        if (pressed) {   // tiron antes de tiempo = MUY mala idea
                            c.mistakes++; c.tension = 0;
                            addPain(c.patient, 4);
                            medic.sendActionBar(net.kyori.adventure.text.Component.text(color("&c¡Tirón en falso! El paciente aúlla. Vuelve a sujetar.")));
                            try { medic.playSound(medic.getLocation(), "entity.player.hurt", 0.8f, 0.8f); } catch (Throwable ignored) { }
                        } else {
                            c.tension++;
                            int leftSecs = Math.max(0, (tensionNeeded - c.tension + 3) / 4);
                            medic.sendActionBar(net.kyori.adventure.text.Component.text(color(
                                    "&eSujeta firme " + c.part.display + "… &f" + (leftSecs > 0 ? leftSecs + "…" : "¡ya casi!"))));
                            if (c.tension >= tensionNeeded) {
                                c.windowTicks = 7;   // ~1.75s para el tiron
                                try { medic.playSound(medic.getLocation(), "block.note_block.bell", 1f, 1.2f); } catch (Throwable ignored) { }
                            }
                        }
                    }
                    if (c.bar != null) c.bar.setProgress(Math.min(1.0, c.tension / (double) Math.max(1, tensionNeeded)));
                }
                default -> { c.ticks++; barAndPct(c, medic, "&eTratando " + c.part.display + "…"); }
            }

            // ── ¿completado? ──
            boolean done = (c.stepsNeeded > 0) ? c.stepsDone >= c.stepsNeeded : c.ticks >= c.needed;
            if (done) {
                channels.remove(c.medic);
                c.bar.removeAll();
                // el RESULTADO depende de COMO lo hiciste: sin errores baja mucho el fallo, cada error lo sube
                double pFinal = Math.max(0.02, Math.min(0.85, c.pFail * 0.5 + c.mistakes * 0.06));
                if (ThreadLocalRandom.current().nextDouble() < pFinal) channelFail(medic, patient, c);
                else {
                    channelDone.add(channelKey(c.medic, c.patient, c.part));
                    treat(medic, patient, c.part);   // ejecuta el tratamiento real (salta el gate)
                }
            }
        }
    }

    /** Delta de mira (grados) desde el ultimo tick, con wrap de yaw. */
    private double lookDelta(Channel c, Player medic) {
        float yaw = medic.getLocation().getYaw(), pitch = medic.getLocation().getPitch();
        if (!c.hasLook) { c.hasLook = true; c.lastYaw = yaw; c.lastPitch = pitch; return 0; }
        double dy = Math.abs(((yaw - c.lastYaw + 540) % 360) - 180);
        double dp = Math.abs(pitch - c.lastPitch);
        c.lastYaw = yaw; c.lastPitch = pitch;
        return dy + dp;
    }

    private void barAndPct(Channel c, Player medic, String txt) {
        if (c.bar != null) c.bar.setProgress(Math.min(1.0, c.ticks / (double) c.needed));
        int pct = (int) Math.min(100, Math.round(100.0 * c.ticks / c.needed));
        medic.sendActionBar(net.kyori.adventure.text.Component.text(color(txt + " &f" + pct + "%")));
    }

    private void interruptChannel(Channel c, Player medic, String reason) {
        channels.remove(c.medic);
        if (c.bar != null) c.bar.removeAll();
        String key = reason.equals("GOLPE") ? "INTERRUMPIDO_GOLPE" : "INTERRUMPIDO_MOVER";
        medic.sendMessage(color("&c" + msgs.pickOr(key,
                "Tratamiento interrumpido. Hay que volver a empezar.",
                "Se te va el pulso: el tratamiento se corta.")));
        try { medic.playSound(medic.getLocation(), "block.note_block.bass", 1f, 0.6f); } catch (Throwable ignored) { }
    }

    /** ¿El médico tiene un tratamiento canalizado en curso? (para no reabrir la ficha sobre el minijuego) */
    public boolean isChanneling(UUID medic) { return channels.containsKey(medic); }

    /** Interrupcion por DAÑO (lo llama BodyListener al recibir un golpe). */
    public void interruptOnDamage(UUID medic) {
        Channel c = channels.get(medic);
        if (c == null) return;
        Player m = Bukkit.getPlayer(medic);
        if (m != null) interruptChannel(c, m, "GOLPE");
    }

    private void channelFail(Player medic, Player patient, Channel c) {
        PartState s = body(patient.getUniqueId()).get(c.part);
        // gasta el material igual (el intento fallido consume) y EMPEORA segun gravedad
        addPain(patient.getUniqueId(), 12);
        s.infectionRisk = Math.min(1, s.infectionRisk + 0.15);
        if (c.kind.equals("BALA") || c.kind.equals("FRACTURA")) {
            s.hp = Math.max(0, s.hp - 8);
            s.bleeding = Math.min(2.0, s.bleeding + 0.4);
        }
        medic.sendMessage(color("&c" + msgs.pickOr("CURA_FALLO",
                "Te tiembla el pulso y la herida se abre más. Has fallado.",
                "Se te va la mano: el tratamiento sale mal y empeora.")));
        if (!medic.getUniqueId().equals(patient.getUniqueId()))
            patient.sendMessage(color("&cTe tratan mal: la herida empeora."));
        try { medic.playSound(medic.getLocation(), "entity.player.hurt", 1f, 0.7f); } catch (Throwable ignored) { }
    }

    /** Cancela canales de un jugador (al desconectar / morir / quit). */
    public void cancelChannels(UUID id) {
        Channel c = channels.remove(id);
        if (c != null && c.bar != null) c.bar.removeAll();
        channels.values().removeIf(ch -> {   // tambien si era el PACIENTE de un canal ajeno
            if (ch.patient.equals(id)) { if (ch.bar != null) ch.bar.removeAll(); return true; }
            return false;
        });
    }

    /** Venda nueva: marca el momento — a los 20 min se ENSUCIA y deja de curar (estilo Zomboid). */
    private void vendar(PartState s) {
        s.vendada = true;
        s.vendadaAt = System.currentTimeMillis();
    }

    private static final long VENDA_SUCIA_MS = 20 * 60_000L;   // vida util de un vendaje

    /** Texto del yml (variante IA) si existe Y es tratamiento a uno mismo; si no, el integrado. */
    private String selfTpl(boolean self, String key, String tpl) {
        if (!self) return tpl;
        String s = msgs.pick(key);
        return s != null ? "&a" + s : tpl;
    }

    public EnumMap<BodyPart, PartState> body(UUID id) {
        return bodies.computeIfAbsent(id, k -> {
            EnumMap<BodyPart, PartState> m = new EnumMap<>(BodyPart.class);
            for (BodyPart p : BodyPart.values()) m.put(p, new PartState());
            return m;
        });
    }

    public void reset(UUID id) {
        bodies.remove(id);
        dolor.remove(id);
        painkillerUntil.remove(id);
        msgCd.remove(id);
    }

    /** Curacion TOTAL (hospital): cuerpo nuevo y sin dolor. El effectsTick restaura los corazones. */
    public void healAll(UUID id) {
        bodies.remove(id);
        dolor.remove(id);
        painkillerUntil.remove(id);
    }

    /** Limpieza al desconectar (anti-fuga): SOLO los auxiliares — las HERIDAS sobreviven al relog. */
    public void onQuit(UUID id) {
        msgCd.remove(id);
        pkUses.remove(id);
        lastXpFrom.remove(id);
    }

    // ─── DOLOR ───────────────────────────────────────────────
    public double painOf(UUID id) { return dolor.getOrDefault(id, 0.0); }
    public boolean painkillerActive(UUID id) {
        Long t = painkillerUntil.get(id);
        return t != null && t > System.currentTimeMillis();
    }
    public void addPain(UUID id, double amount) {
        dolor.merge(id, amount, (a, b) -> Math.max(0, Math.min(100, a + b)));
    }
    private final Map<UUID, java.util.ArrayDeque<Long>> pkUses = new ConcurrentHashMap<>();

    /** Analgesico: clic derecho. 2 min de tregua + baja el dolor. 3+ en 15 min = SOBREDOSIS (ChatGPT r18). */
    public void takePainkiller(Player p) {
        long now = System.currentTimeMillis();
        var uses = pkUses.computeIfAbsent(p.getUniqueId(), k -> new java.util.ArrayDeque<>());
        while (!uses.isEmpty() && now - uses.peekFirst() > 900_000L) uses.pollFirst();
        uses.addLast(now);
        if (uses.size() >= 3) {
            addPain(p.getUniqueId(), 12);
            try { p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 200, 0, false, false)); } catch (Throwable ignored) { }
            p.sendMessage(color("&5Demasiadas pastillas en poco tiempo: el estómago se revuelve y el corazón se acelera. SOBREDOSIS."));
            return;
        }
        painkillerUntil.put(p.getUniqueId(), System.currentTimeMillis() + 120_000L);
        addPain(p.getUniqueId(), -45);
        p.sendMessage(color("&6" + msgs.pickOr("ANALGESICO_OK",
                "Tragas los analgésicos en seco. El dolor se aleja... un rato.",
                "Las pastillas hacen efecto: el cuerpo sigue roto, pero ya no grita.",
                "Dos minutos de paz química. Aprovéchalos.")));
        try { p.playSound(p.getLocation(), "entity.generic.drink", 0.8f, 1.3f); } catch (Throwable ignored) { }
    }

    // ─── DANO LOCALIZADO (acumulativo por gravedad) ──────────
    /** Aplica una herida. Para FRACTURA/ESGUINCE/QUEMADURA, severity = GRADO (1-3 / 1-2). Devuelve mult de bala. */
    public double wound(Player victim, BodyPart part, WoundType type, double severity) {
        PartState s = body(victim.getUniqueId()).get(part);
        UUID id = victim.getUniqueId();
        s.vendada = false;   // toda herida nueva arranca o ensucia el vendaje
        double mult = 1.0;
        switch (type) {
            case BALA -> {
                mult = part.gunMult;
                s.hp = Math.max(0, s.hp - severity * 6.5 * mult);
                s.bleeding = Math.min(2.0, s.bleeding + (part.isVital() ? 0.8 : 0.4));
                boolean profunda = severity * mult >= 1.3;
                if (s.balaDentro) {       // segunda bala en la misma parte: carniceria
                    s.balaProfunda = true;
                    s.bleeding = Math.min(2.0, s.bleeding + 0.4);
                    msgNow(victim, "&4OTRA bala en {parte}. Esto ya es un colador: solo un cirujano lo arregla.", part);
                } else {
                    s.balaDentro = true;
                    s.balaProfunda = profunda;
                    String balaKey = (part == BodyPart.CABEZA || part == BodyPart.CUELLO) ? "BALA_CABEZA"
                            : part == BodyPart.INGLE ? "BALA_INGLE"
                            : (part == BodyPart.MANO_IZQ || part == BodyPart.MANO_DER) ? "BALA_MANO"
                            : part.isBrazo() ? "BALA_BRAZO"
                            : part.isPierna() ? "BALA_PIERNA" : "BALA_TORSO";
                    String fromYml = msgs.pick(balaKey);
                    msgNow(victim, fromYml != null ? "&c" + fromYml
                            : part.isPierna() ? "&cLa bala te muerde {parte}: la pierna cede bajo tu peso."
                            : part == BodyPart.CABEZA ? "&4Un fogonazo blanco: la bala te rozó {parte}. Se te nubla todo."
                            : part.isBrazo() ? "&cLa bala te atraviesa {parte}: los dedos dejan de responderte."
                            : "&cSientes el plomo entrar en {parte}. El calor, luego el dolor.", part);
                    if (profunda) msgNow(victim, "&8(la notas DENTRO, profunda... esto pide bisturí, no pinzas)", part);
                }
                s.infectionRisk = Math.min(1, s.infectionRisk + 0.15);
                addPain(id, 18 * mult);
            }
            case CORTE -> {
                s.hp = Math.max(0, s.hp - severity * 4.0);
                boolean profundo = severity > 1.5;
                s.bleeding = Math.min(1.5, s.bleeding + (profundo ? 0.5 : 0.2));
                s.infectionRisk = Math.min(1, s.infectionRisk + 0.10);
                if (profundo && !s.corteProfundo) {
                    s.corteProfundo = true;
                    msgNow(victim, "&cUn tajo profundo te abre {parte}: los bordes no van a cerrar solos. Sutura.", part);
                } else {
                    msg(victim, "corte", part, pick(
                            "&eUn corte en {parte}. Escuece, pero vivirás.",
                            "&eTe llevas un tajo en {parte}. Nada que una venda no calle.",
                            "&eSangre nueva en {parte}: corte superficial."));
                }
                addPain(id, profundo ? 10 : 4);
            }
            case CONTUSION -> {
                int grade = (int) Math.max(1, Math.min(3, Math.round(severity)));
                if (s.contusion > 0) {
                    int before = s.contusion;
                    s.contusion = Math.min(3, Math.max(s.contusion, grade) + (s.contusion >= grade ? 1 : 0));
                    s.hp = Math.max(0, s.hp - grade * 2.0);
                    msg(victim, s.contusion > before ? "cont_esc" : "cont_rep", part, s.contusion > before
                            ? "&6Otro golpe en {parte}: el moratón va a peor."
                            : "&eVuelves a castigar {parte}. Ya dolía.");
                } else {
                    s.contusion = grade;
                    s.hp = Math.max(0, s.hp - grade * 2.0);
                    msg(victim, "contusion", part, grade >= 3
                            ? "&6Un golpe brutal en {parte}: contusión fuerte, se hincha al momento."
                            : grade == 2 ? "&eUn golpe seco en {parte}: mañana saldrá un buen moratón."
                            : "&eUn raspón en {parte}. Escuece, pero poca cosa.");
                }
                addPain(id, 2 + grade * 3);
            }
            case FRACTURA -> {
                int grade = (int) Math.max(1, Math.min(3, Math.round(severity)));
                if (s.fractura > 0) {
                    // ACUMULACION: volver a castigar un hueso tocado lo EMPEORA, no repite
                    int before = s.fractura;
                    s.fractura = Math.min(3, Math.max(s.fractura + 1, grade));
                    s.hp = Math.max(0, s.hp - 12);
                    if (s.fractura > before) {
                        msgNow(victim, s.fractura >= 3
                                ? "&4El hueso de {parte} CRUJE y algo asoma donde no debería. Fractura ABIERTA: ni se te ocurra andar."
                                : "&4{parte} vuelve a crujir: la fisura ya es FRACTURA. Cada vez peor.", part);
                        if (s.fractura >= 3) s.bleeding = Math.min(1.5, s.bleeding + 0.4);
                    } else {
                        msg(victim, "fract_rep", part, "&cCastigas {parte} otra vez. El hueso protesta, pero aguanta... de milagro.");
                    }
                } else {
                    s.fractura = grade;
                    s.hp = Math.max(0, s.hp - grade * 7.0);
                    msgNow(victim, grade >= 3 ? "&4CRACK. {parte} se rompe de mala manera: fractura ABIERTA. Sangra y asoma hueso."
                            : grade == 2 ? "&4CRACK. Algo se ha roto en {parte}. No puedes apoyar sin ver las estrellas."
                            : "&cUn chasquido fino en {parte}: fisura. Si la fuerzas, se rompe del todo.", part);
                    if (grade >= 3) { s.bleeding = Math.min(1.5, s.bleeding + 0.5); s.infectionRisk = Math.min(1, s.infectionRisk + 0.12); }
                }
                try { victim.playSound(victim.getLocation(), "entity.item.break", 1f, 0.6f); } catch (Throwable ignored) { }
                addPain(id, 10 + s.fractura * 8);
            }
            case ESGUINCE -> {
                int grade = (int) Math.max(1, Math.min(2, Math.round(severity)));
                if (s.esguince > 0 || s.fractura > 0) {
                    // tobillo ya tocado + nuevo mal apoyo = sube de grado o pasa a fisura
                    if (s.esguince >= 2 && s.fractura == 0) {
                        s.esguince = 0;
                        s.fractura = 1;
                        s.hp = Math.max(0, s.hp - 8);
                        msgNow(victim, "&cEl esguince de {parte} no aguanta otro mal gesto: ahora es una FISURA.", part);
                    } else {
                        s.esguince = Math.min(2, s.esguince + 1);
                        s.hp = Math.max(0, s.hp - 4);
                        msg(victim, "esg_rep", part, "&eOtra vez {parte}... la articulación está cada vez más hinchada.");
                    }
                } else {
                    s.esguince = grade;
                    s.hp = Math.max(0, s.hp - grade * 3.0);
                    msg(victim, "esguince", part, pick(
                            "&eMal apoyo: {parte} se dobla donde no debía. Esguince.",
                            "&e{parte} se tuerce con un latigazo de dolor. Esguince.",
                            "&eNotas el tirón en {parte}: esguince. Véndalo y no lo fuerces."));
                }
                addPain(id, 4 + s.esguince * 3);
            }
            case QUEMADURA -> {
                int grade = (int) Math.max(1, Math.min(2, Math.round(severity)));
                if (s.quemadura > 0) {
                    s.quemadura = 2;
                    s.hp = Math.max(0, s.hp - 6);
                    msg(victim, "quem_rep", part, "&6La piel ya quemada de {parte} vuelve a arder. Esto deja marca.");
                } else {
                    s.quemadura = grade;
                    s.hp = Math.max(0, s.hp - grade * 4.0);
                    msg(victim, "quemadura", part, pick(
                            "&6La piel de {parte} arde y se tensa. Quemadura fea.",
                            "&6Olor a quemado. Es tu propia piel, en {parte}.",
                            "&6{parte} se lleva un fogonazo: la piel queda en carne viva."));
                }
                s.infectionRisk = Math.min(1, s.infectionRisk + 0.08);
                addPain(id, 6 + grade * 4);
            }
        }
        return mult;
    }

    // ─── TICKS ───────────────────────────────────────────────
    /** Cada 10s: el sangrado drena vida de verdad (mas cuanto mas grave). */
    public void bleedTick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID pid = p.getUniqueId();
            EnumMap<BodyPart, PartState> b = bodies.get(pid);
            if (b == null) continue;
            // ya derribado: el bleedout lo lleva DownedManager, el sangrado no debe tocar su vida
            try { if (NemelesApi.combat().isDowned(pid)) continue; } catch (Throwable ignored) { }
            double bleed = 0;
            for (PartState s : b.values()) bleed += s.bleeding;
            if (bleed <= 0) continue;
            double dmg = Math.min(6.0, bleed * 2.0);
            // EL SANGRADO NUNCA MATA INSTANTANEO: al llegar a <=1/2 corazon, DERRIBA (downed) en vez de matar
            if (p.getHealth() - dmg <= 1.0) {
                try { p.setHealth(Math.max(1.0, p.getHealth())); } catch (IllegalArgumentException ignored) { }
                try { NemelesApi.combat().forceDown(pid, null, "SANGRADO"); } catch (Throwable ignored) { }
                continue;
            }
            try {
                p.damage(dmg);
                p.getWorld().spawnParticle(org.bukkit.Particle.DUST, p.getLocation().add(0, 1, 0), 6, 0.2, 0.4, 0.2,
                        new org.bukkit.Particle.DustOptions(org.bukkit.Color.fromRGB(150, 10, 10), 1.0f));
            } catch (Throwable ignored) { }
            String key = bleed >= 0.8 ? "bleed_grave" : "bleed";
            msgKeyed(p, key, 30_000L, bleed >= 0.8
                    ? "&4" + msgs.pickOr("SANGRADO_GRAVE",
                           "Estás perdiendo MUCHA sangre. Venda estéril o torniquete, YA.",
                           "El suelo va dejando un rastro rojo detrás de ti. Esto no espera.",
                           "Se te empieza a nublar la vista: el sangrado es GRAVE.")
                    : "&c" + msgs.pickOr("SANGRADO_AVISO",
                           "Estás sangrando. Cada minuto sin venda es vida que se va.",
                           "La herida sigue abierta, gota a gota. Búscate una venda.",
                           "Notas la ropa pegada y caliente: sigues sangrando."));
        }
    }

    /** Cada 60s: infecciones avanzan; SOLO las partes VENDADAS regeneran; el dolor afloja. */
    public void infectionTick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            EnumMap<BodyPart, PartState> b = bodies.get(id);
            if (b == null) continue;
            for (Map.Entry<BodyPart, PartState> e : b.entrySet()) {
                PartState s = e.getValue();
                if (!s.infectado && s.infectionRisk > 0 && (s.balaDentro || s.bleeding > 0 || s.quemadura > 0 || s.corteProfundo)) {
                    if (ThreadLocalRandom.current().nextDouble() < s.infectionRisk * 0.10) {
                        s.infectado = true;
                        s.vendada = false;
                        msgNow(p, "&2{parte} está caliente, hinchada y huele mal... INFECTADA. Antibiótico o médico.", e.getKey());
                        try { p.playSound(p.getLocation(), "entity.zombie_villager.cure", 0.6f, 0.5f); } catch (Throwable ignored) { }
                    }
                }
                if (s.infectado) {
                    s.hp = Math.max(0, s.hp - 1.5);          // la infeccion come la parte
                    continue;
                }
                // la venda se ENSUCIA: a los 20 min deja de curar y ademas suma riesgo de infeccion
                if (s.vendada && s.vendadaAt > 0 && System.currentTimeMillis() - s.vendadaAt > VENDA_SUCIA_MS) {
                    s.vendada = false;
                    if (s.hp < 100) {
                        s.infectionRisk = Math.min(1, s.infectionRisk + 0.05);
                        msg(p, "venda_sucia", e.getKey(), "&e" + msgs.pickOr("VENDA_SUCIA",
                                "La venda de {parte} está sucia y rígida: ya no cura. Cámbiala.",
                                "El vendaje de {parte} huele raro. Una venda sucia es peor que ninguna.",
                                "La venda de {parte} se ha empapado y endurecido: toca cambiarla."));
                    }
                }
                // SIN regeneracion natural: SOLO una parte vendada, sin bala/corte abierto y bien comido cura
                if (s.vendada && s.hp < 100 && !s.balaDentro && !s.corteProfundo && s.bleeding <= 0
                        && p.getFoodLevel() >= 14) {
                    s.hp = Math.min(100, s.hp + 1.2);
                    if (s.hp >= 70 && s.esguince > 0) {
                        s.esguince--;
                        if (s.esguince == 0) msg(p, "heal_esg", e.getKey(), "&a{parte} ya apoya sin quejarse: el esguince cede.");
                    }
                    if (s.hp >= 55 && s.contusion > 0) {       // moratones/raspones se van con reposo
                        s.contusion--;
                        if (s.contusion == 0) msg(p, "heal_cont", e.getKey(), "&aEl moratón de {parte} se va bajando.");
                    }
                    if (s.hp >= 90 && s.fractura == 1) {     // SOLO la fisura suelda con reposo vendado
                        s.fractura = 0;
                        msg(p, "heal_fis", e.getKey(), "&aLa fisura de {parte} ha soldado. Te lo has ganado a base de reposo.");
                    }
                    if (s.hp >= 80 && s.quemadura == 1) s.quemadura = 0;
                    if (s.hp >= 100) {
                        s.vendada = false;                   // venda cumplida
                        msg(p, "heal_full", e.getKey(), "&a{parte} está como nueva. Te quitas la venda.");
                    }
                }
            }
            // el dolor afloja despacio (la morfina de la vida: el tiempo)
            Double d = dolor.get(id);
            if (d != null && d > 0) dolor.put(id, Math.max(0, d - 2.5));
        }
    }

    /** Cada 3s: estado del cuerpo -> efectos REALES (cojera por grados, dolor, debilidad, corazones). */
    public void effectsTick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            EnumMap<BodyPart, PartState> b = bodies.get(id);
            if (b == null) {
                // sin ficha = cuerpo sano: restaura el MAX_HEALTH si quedo reducido y PERSISTIDO
                // en el NBT (p.ej. infeccion + reinicio del servidor borra el mapa en memoria)
                try {
                    var attr = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                    if (attr != null && attr.getBaseValue() != 20.0) attr.setBaseValue(20.0);
                } catch (Throwable ignored) { }
                continue;
            }
            int legTier = 0, fatigue = 0, infectedParts = 0;
            for (Map.Entry<BodyPart, PartState> e : b.entrySet()) {
                PartState s = e.getValue();
                BodyPart part = e.getKey();
                if (s.infectado) infectedParts++;
                if (part.isPierna()) {
                    int t = s.fractura >= 3 ? 3
                            : s.fractura == 2 ? 2
                            : (s.fractura == 1 || s.esguince >= 2) ? 1
                            : (s.esguince == 1 || s.hp <= 35) ? 1 : 0;
                    legTier = Math.max(legTier, t);
                }
                if (part.isBrazo() && (s.fractura >= 2 || s.hp <= 30)) fatigue = Math.max(fatigue, 1);
                if (part == BodyPart.CABEZA && s.hp <= 30) {
                    try { p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 80, 0, false, false)); } catch (Throwable ignored) { }
                }
            }
            double pain = painOf(id);
            boolean pk = painkillerActive(id);
            if (pk && legTier > 0) legTier--;            // los analgesicos te dejan ANDAR pese a la lesion
            if (!pk && pain >= 70) legTier = Math.max(legTier, 1);
            try {
                if (legTier > 0) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, Math.min(2, legTier - 1), false, false));
                    p.setSprinting(false);
                    if (legTier >= 2) msgKeyed(p, "cojera", 25_000L, "&7" + msgs.pickOr("COJERA",
                            "Avanzas a tirones, arrastrando la pierna mala.",
                            "Cada paso es una negociación con el dolor.",
                            "Cojeas. Quien te vea andar sabrá que estás tocado.",
                            "La pierna no responde: medio paso, pausa, medio paso."));
                }
                if (fatigue > 0) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 80, fatigue - 1, false, false));
                    p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 80, 0, false, false));
                }
                if (!pk && pain >= 70) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 80, 0, false, false));
                    msgKeyed(p, "dolor", 35_000L, "&c" + msgs.pickOr("DOLOR_ALTO",
                            "El dolor lo tapa todo: no puedes ni pensar. Unos analgésicos ayudarían.",
                            "Aprietas los dientes. El cuerpo entero te pide parar.",
                            "El dolor te sube en oleadas. Necesitas calmantes o un médico."));
                } else if (!pk && pain >= 40) {
                    msgKeyed(p, "dolor", 45_000L, "&eEl dolor va y viene, sordo, constante. Unos analgésicos no irían mal.");
                }
                // infeccion = corazones maximos perdidos (2 por parte infectada, max 6)
                var attr = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                if (attr != null) {
                    double target = 20.0 - Math.min(6, infectedParts * 2);
                    if (attr.getBaseValue() != target) {
                        attr.setBaseValue(target);
                        if (p.getHealth() > target) p.setHealth(target);
                    }
                }
            } catch (Throwable ignored) { }
        }
    }

    /** Penalizacion de punteria por brazos/manos danados + DOLOR (la lee el GunListener). */
    public double aimSpreadExtra(UUID id) {
        EnumMap<BodyPart, PartState> b = bodies.get(id);
        double extra = 0;
        if (b != null) {
            for (Map.Entry<BodyPart, PartState> e : b.entrySet()) {
                if (!e.getKey().isBrazo()) continue;
                PartState s = e.getValue();
                if (s.fractura >= 2) extra += 4.0;
                else if (s.fractura == 1 || s.hp <= 30) extra += 2.5;
                else if (s.hp <= 60) extra += 1.0;
            }
        }
        if (!painkillerActive(id) && painOf(id) >= 60) extra += 2.0;   // manos temblorosas
        return Math.min(9.0, extra);
    }

    // ─── CURSOS Y NIVEL DE MEDICINA ──────────────────────────
    public int courseOf(Player p) {
        Integer c = p.getPersistentDataContainer().get(courseKey, PersistentDataType.INTEGER);
        return c == null ? 0 : c;
    }

    /** Otorga un titulo (lo llama el EXAMEN al aprobar). */
    public void setCourse(Player p, int level) {
        p.getPersistentDataContainer().set(courseKey, PersistentDataType.INTEGER, level);
    }

    public int medLevel(Player p) {
        try { return NemelesApi.skills().getLevel(p.getUniqueId(), "medic"); } catch (Throwable t) { return 0; }
    }

    // ─── TRATAMIENTO (cada herida pide SU material) ──────────
    /**
     * Trata la parte por prioridad clinica: sangrado -> bala -> corte profundo -> fractura ->
     * infeccion -> desinfectar -> quemadura -> vendar. Lo critico exige OTRO jugador con curso.
     */
    public void treat(Player medic, Player patient, BodyPart part) {
        PartState s = body(patient.getUniqueId()).get(part);
        int course = courseOf(medic);
        int level = medLevel(medic);
        double save = Math.min(0.4, level * 0.008);     // manos expertas: no gastar material
        boolean self = medic.getUniqueId().equals(patient.getUniqueId());
        String pacName = self ? "tu" : "su";

        // ── MINIJUEGO: lo SERIO (cirugía, fracturas, infección) se CANALIZA con una barra,
        // no es instantáneo. Lo leve (venda, pomada, esguince) sigue siendo directo. Si el canal
        // ya terminó con éxito (channelDone), se ejecuta el tratamiento real saltando el gate.
        if (minigameEnabled) {
            String serio = seriousTreatment(s, course, self);
            if (serio != null) {
                if (channelDone.remove(channelKey(medic.getUniqueId(), patient.getUniqueId(), part))) {
                    // viene de un canal completado: cae al switch normal y aplica el tratamiento
                } else {
                    // NO abrir la barra si te falta el material: si no, gastas 7-12s agachado para nada
                    String missing = missingMaterial(medic, s, serio);
                    if (missing != null) { need(medic, missing); return; }
                    beginChannel(medic, patient, part, serio);
                    return;
                }
            }
        }

        // 1) SANGRADO: lo primero, siempre
        if (s.bleeding > 0) {
            if (s.bleeding >= 0.8) {
                // GRAVE: venda esteril (curso 1) o torniquete (cualquiera, solo extremidades)
                if (MedItems.has(medic, MedItems.VENDA_ESTERIL) && course >= 1) {
                    MedItems.consume(medic, MedItems.VENDA_ESTERIL, save);
                    s.bleeding = 0;
                    s.infectionRisk = 0;
                    vendar(s);
                    s.hp = Math.min(100, s.hp + 12 + level * 0.4);
                    done(medic, patient, selfTpl(self, "VENDA_OK",
                            "&aCierras el sangrado de {parte} con venda estéril. Limpio y firme."), part, 14);
                } else if (MedItems.has(medic, MedItems.TORNIQUETE) && (part.isPierna() || part.isBrazo())) {
                    MedItems.consume(medic, MedItems.TORNIQUETE, 0);
                    s.bleeding = 0;
                    s.hp = Math.max(5, s.hp - 6);
                    addPain(patient.getUniqueId(), 12);
                    done(medic, patient, selfTpl(self, "TORNIQUETE_OK",
                            "&6Aprietas el torniquete en {parte} hasta que deja de sangrar. Duele, pero salva."), part, 8);
                } else {
                    medic.sendMessage(color("&cSangrado GRAVE: pide &bvenda estéril&c (curso básico) o &ctorniquete&c (en brazo/pierna)."));
                }
                return;
            }
            if (s.bleeding >= 0.35 && course < 1) {
                medic.sendMessage(color("&cEse sangrado pide manos formadas: curso básico (/medicina estudiar basico). Para uno leve bastaría una venda."));
                return;
            }
            if (!MedItems.consume(medic, MedItems.VENDA, save)) { need(medic, MedItems.VENDA); return; }
            s.bleeding = 0;
            vendar(s);
            s.hp = Math.min(100, s.hp + 8 + level * 0.4);
            done(medic, patient, selfTpl(self, "VENDA_OK",
                    "&aVendas " + pacName + " herida en {parte}: el sangrado se corta."), part, 8);

        // 2) INFECCION declarada: la medicacion NO es cirugia — va ANTES que la bala/fractura,
        // o un solitario con bala profunda + infeccion jamas podria frenar la infeccion (review r24)
        } else if (s.infectado) {
            if (course < 2) { medic.sendMessage(color("&cUna infección seria pide el curso AVANZADO (o un médico de verdad).")); return; }
            if (s.hp <= 20 && self) { medic.sendMessage(color("&cLa infección está demasiado avanzada para tratártela solo. Que te atienda OTRO médico.")); return; }
            if (!MedItems.consume(medic, MedItems.ANTIBIOTICO, save)) { need(medic, MedItems.ANTIBIOTICO); return; }
            s.infectado = false;
            s.infectionRisk = 0;
            vendar(s);
            s.hp = Math.min(100, s.hp + 10 + level * 0.4);
            done(medic, patient, "&aLimpias y medicas {parte}: la infección remite. Buen trabajo.", part, 25);

        // 3) BALA dentro
        } else if (s.balaDentro) {
            if (course < 2) { medic.sendMessage(color("&cHay una BALA dentro: extraerla pide el curso AVANZADO.")); return; }
            if (s.balaProfunda) {
                if (self) { medic.sendMessage(color("&cEstá demasiado profunda para operarte TÚ MISMO. Necesitas otro médico (curso avanzado).")); return; }
                if (!MedItems.has(medic, MedItems.BISTURI)) { need(medic, MedItems.BISTURI); return; }
                if (!MedItems.consume(medic, MedItems.SUTURA, save)) { need(medic, MedItems.SUTURA); return; }
                if (ThreadLocalRandom.current().nextDouble() < Math.max(0.05, 0.40 - level * 0.012)) {
                    s.hp = Math.max(0, s.hp - 10);
                    addPain(patient.getUniqueId(), 15);
                    patient.sendMessage(color("&cEl bisturí busca... y resbala. Muerde un trapo."));
                }
                s.balaDentro = false;
                s.balaProfunda = false;
                s.infectionRisk = Math.max(0, s.infectionRisk - 0.20);
                s.bleeding = Math.min(1.0, s.bleeding + 0.2);   // la cirugia abre: vendar despues
                s.hp = Math.min(100, s.hp + 5 + level * 0.3);
                done(medic, patient, "&aAbres, extraes el plomo de {parte} y suturas. Tin. La bala cae en la bandeja.", part, 30);
                medic.sendMessage(color("&7La incisión sangra un poco: véndala para terminar."));
            } else {
                if (!MedItems.has(medic, MedItems.PINZAS)) { need(medic, MedItems.PINZAS); return; }
                if (ThreadLocalRandom.current().nextDouble() < Math.max(0.05, 0.35 - level * 0.012)) {
                    s.hp = Math.max(0, s.hp - 8);
                    addPain(patient.getUniqueId(), 10);
                    patient.sendMessage(color("&cLas pinzas hurgan... y resbalan. Aprieta los dientes."));
                }
                s.balaDentro = false;
                s.infectionRisk = Math.max(0, s.infectionRisk - 0.15);
                s.hp = Math.min(100, s.hp + 5 + level * 0.3);
                done(medic, patient, "&aExtraes el plomo de {parte}. Tin. La bala cae al suelo.", part, 20);
            }

        // 3) CORTE PROFUNDO: sutura antes de nada
        } else if (s.corteProfundo) {
            if (course < 2) { medic.sendMessage(color("&cEse tajo pide SUTURA y pulso: curso AVANZADO.")); return; }
            if (!MedItems.consume(medic, MedItems.SUTURA, save)) { need(medic, MedItems.SUTURA); return; }
            s.corteProfundo = false;
            s.hp = Math.min(100, s.hp + 8 + level * 0.3);
            done(medic, patient, selfTpl(self, "SUTURA_OK",
                    "&aPunto a punto cierras el tajo de {parte}. Cicatriz para contarlo."), part, 18);

        // 4) FRACTURA (por grados)
        } else if (s.fractura > 0) {
            if (course < 1) { medic.sendMessage(color("&cUn hueso roto sin formación es un destrozo: curso básico primero.")); return; }
            if (s.fractura >= 3) {
                if (self) { medic.sendMessage(color("&cFractura ABIERTA: no puedes recolocarte el hueso tú solo. Busca un médico avanzado.")); return; }
                if (course < 2) { medic.sendMessage(color("&cUna fractura ABIERTA pide curso AVANZADO.")); return; }
                if (!MedItems.consume(medic, MedItems.SUTURA, save)) { need(medic, MedItems.SUTURA); return; }
                if (!MedItems.consume(medic, MedItems.FERULA, save)) { need(medic, MedItems.FERULA); return; }
                s.fractura = 0;
                s.bleeding = 0;
                s.hp = Math.max(s.hp, 30);
                vendar(s);
                addPain(patient.getUniqueId(), 15);
                done(medic, patient, "&aRecolocas el hueso de {parte}, cierras y entablillas. Trabajo de quirófano callejero.", part, 35);
            } else if (s.fractura == 2) {
                if (!MedItems.has(medic, MedItems.ANALGESICO)) {
                    medic.sendMessage(color("&cRecolocar esa fractura SIN analgésicos sería una tortura. Trae &6analgésicos&c y una férula."));
                    return;
                }
                if (!MedItems.consume(medic, MedItems.FERULA, save)) { need(medic, MedItems.FERULA); return; }
                MedItems.consume(medic, MedItems.ANALGESICO, save);
                s.fractura = 0;
                s.hp = Math.max(s.hp, 40);
                vendar(s);
                done(medic, patient, selfTpl(self, "FERULA_OK",
                        "&aAnalgésico, tirón seco y férula: el hueso de {parte} vuelve a su sitio."), part, 22);
            } else {
                if (!MedItems.consume(medic, MedItems.FERULA, save)) { need(medic, MedItems.FERULA); return; }
                s.fractura = 0;
                s.hp = Math.max(s.hp, 50);
                vendar(s);
                done(medic, patient, selfTpl(self, "FERULA_OK",
                        "&aEntablillas la fisura de {parte}. Reposo y no la fuerces."), part, 12);
            }

        // 6) herida sucia sin infectar -> desinfectar a tiempo
        } else if (s.infectionRisk >= 0.10 && MedItems.has(medic, MedItems.DESINFECTANTE)) {
            MedItems.consume(medic, MedItems.DESINFECTANTE, save);
            s.infectionRisk = 0;
            done(medic, patient, selfTpl(self, "DESINFECTANTE_OK",
                    "&bEl desinfectante muerde en {parte}... pero esa herida ya no se infecta."), part, 6);

        // 7) QUEMADURA
        } else if (s.quemadura > 0) {
            if (s.quemadura >= 2 && course < 1) { medic.sendMessage(color("&cUna quemadura profunda pide curso básico.")); return; }
            if (!MedItems.consume(medic, MedItems.POMADA, save)) { need(medic, MedItems.POMADA); return; }
            s.quemadura = 0;
            vendar(s);
            s.hp = Math.min(100, s.hp + 10 + level * 0.4);
            done(medic, patient, selfTpl(self, "POMADA_OK",
                    "&aExtiendes la pomada sobre {parte}: la piel respira por fin."), part, 10);

        // 8) resto: vendar para que REGENERE (sin venda, nada cura)
        } else if (s.esguince > 0 || s.hp < 100) {
            if (s.vendada) { medic.sendMessage(color("&7{parte} ya está vendada: dale tiempo (y comida).".replace("{parte}", part.display))); return; }
            if (!MedItems.consume(medic, MedItems.VENDA, save)) { need(medic, MedItems.VENDA); return; }
            vendar(s);
            s.hp = Math.min(100, s.hp + 6 + level * 0.3);
            done(medic, patient, selfTpl(self, "VENDA_OK",
                    "&aCubres {parte} con una venda limpia. Ahora sí puede curar."), part, 6);
        } else {
            medic.sendMessage(color("&7Esa parte está sana. No le des más vueltas."));
        }
    }

    private void need(Player medic, String medId) {
        medic.sendMessage(color("&cTe falta " + MedItems.display(medId) + "&c para ese tratamiento. &8(farmacia)"));
    }

    // anti-farmeo de XP (balance ChatGPT r18): self-treat x0.10, mismo paciente en 20 min x0.25,
    // soft-cap diario 2600 -> x0.35. Curar de verdad a gente distinta es lo que paga.
    private final Map<UUID, Map<UUID, Long>> lastXpFrom = new ConcurrentHashMap<>();
    private final Map<UUID, long[]> xpToday = new ConcurrentHashMap<>();   // [epochDay, xp]

    private void grantMedXp(Player medic, Player patient, int baseXp) {
        double mult = 1.0;
        boolean self = medic.getUniqueId().equals(patient.getUniqueId());
        if (self) mult *= 0.10;
        else {
            Map<UUID, Long> per = lastXpFrom.computeIfAbsent(medic.getUniqueId(), k -> new ConcurrentHashMap<>());
            Long last = per.get(patient.getUniqueId());
            long now = System.currentTimeMillis();
            if (last != null && now - last < 20 * 60_000L) mult *= 0.25;
            per.put(patient.getUniqueId(), now);
        }
        long day = System.currentTimeMillis() / 86_400_000L;
        long[] t = xpToday.compute(medic.getUniqueId(), (k, v) -> (v == null || v[0] != day) ? new long[]{day, 0} : v);
        if (t[1] >= 2600) mult *= 0.35;
        int xp = (int) Math.round(baseXp * mult);
        if (xp <= 0) return;
        t[1] += xp;
        try { NemelesApi.skills().grantXp(medic, "medic", xp); } catch (Throwable ignored) { }
    }

    private void done(Player medic, Player patient, String tpl, BodyPart part, int xp) {
        msgNow(medic, tpl, part);
        if (!medic.getUniqueId().equals(patient.getUniqueId())) {
            msgNow(patient, tpl.replace("Vendas", "Te vendan").replace("Extraes", "Te extraen")
                    .replace("Abres, extraes", "Te abren, te extraen")
                    .replace("Entablillas", "Te entablillan").replace("Limpias y medicas", "Te limpian y medican")
                    .replace("Cierras", "Te cierran").replace("Recolocas", "Te recolocan")
                    .replace("Aprietas", "Te aprietan").replace("Cubres", "Te cubren")
                    .replace("Extiendes", "Te extienden"), part);
        }
        grantMedXp(medic, patient, xp);
        try { medic.playSound(medic.getLocation(), "entity.villager.work_cleric", 0.8f, 1.2f); } catch (Throwable ignored) { }
    }

    /** Check-in del HOSPITAL (cura al 65%, NO quita infecciones — balance ChatGPT r18). */
    public void hospitalTreat(UUID id) {
        EnumMap<BodyPart, PartState> b = body(id);
        for (PartState s : b.values()) {
            s.bleeding = 0;
            s.balaDentro = false;
            s.balaProfunda = false;
            s.fractura = 0;
            s.esguince = 0;
            s.quemadura = 0;
            s.corteProfundo = false;
            s.infectionRisk = 0;
            s.hp = Math.max(s.hp, 65);
            vendar(s);
            // s.infectado se queda: la infeccion declarada pide ANTIBIOTICOS, ni el hospital la regala
        }
        Double d = dolor.get(id);
        if (d != null && d > 35) dolor.put(id, 35.0);
    }

    // ─── mensajes (anti-spam con variantes) ──────────────────
    /** Mensaje SIN throttle (eventos importantes: herida nueva, escalada, tratamiento). */
    private void msgNow(Player p, String tpl, BodyPart part) {
        p.sendMessage(color(tpl.replace("{parte}", part.display)));
    }

    /** Mensaje con throttle por parte+tipo (45s): las heridas repetidas no ametrallan el chat. */
    private void msg(Player p, String key, BodyPart part, String tpl) {
        msgKeyed(p, key + "|" + part.name(), 45_000L, tpl.replace("{parte}", part.display));
    }

    private void msgKeyed(Player p, String key, long cdMillis, String text) {
        Map<String, Long> cds = msgCd.computeIfAbsent(p.getUniqueId(), k -> new ConcurrentHashMap<>());
        long now = System.currentTimeMillis();
        Long last = cds.get(key);
        if (last != null && now - last < cdMillis) return;
        cds.put(key, now);
        p.sendMessage(color(text));
    }

    private static String pick(String... variants) {
        return variants[ThreadLocalRandom.current().nextInt(variants.length)];
    }

    private static String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
}
