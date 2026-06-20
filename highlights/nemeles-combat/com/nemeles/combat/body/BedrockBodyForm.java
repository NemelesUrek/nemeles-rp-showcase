package com.nemeles.combat.body;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * FALLBACK BEDROCK de la FICHA MÉDICA (/cuerpo) — menú nativo (SimpleForm de Cumulus) por REFLEXIÓN.
 *
 * <p>En Bedrock el cofre radiográfico (fuente custom + slots transparentes) no se renderiza por
 * Geyser y el clic es torpe; con Geyser/Floodgate abrimos un menú TÁCTIL: un botón por PARTE
 * lesionada (su estado va en el label) y, al pulsar, una segunda hoja con VER DETALLE / TRATAR que
 * ejecuta EL MISMO backend que el cofre (BodyGUI.chatDetail + BodyManager.treat — sin duplicar
 * lógica clínica). Es exactamente el patrón de MarketManager.openBedrock/isBedrock.</p>
 *
 * <p>Sin dependencia de compilación: si Floodgate/Cumulus no están en runtime, {@link #open} y
 * {@link #isBedrock} devuelven false y BodyGUI cae al chat clicable (sin regresión).</p>
 */
public final class BedrockBodyForm {

    private BedrockBodyForm() { }

    /** Mismo orden que la ficha por chat (cabeza -> pies). */
    private static final BodyPart[] ORDER = {
            BodyPart.CABEZA, BodyPart.CUELLO, BodyPart.TORSO_SUP, BodyPart.TORSO_INF, BodyPart.INGLE,
            BodyPart.BRAZO_DER, BodyPart.BRAZO_IZQ, BodyPart.MANO_DER, BodyPart.MANO_IZQ,
            BodyPart.MUSLO_DER, BodyPart.MUSLO_IZQ, BodyPart.ESPINILLA_DER, BodyPart.ESPINILLA_IZQ,
            BodyPart.PIE_DER, BodyPart.PIE_IZQ };

    /** Plugin para programar el tratamiento en el hilo principal (la respuesta de Floodgate llega async). */
    private static org.bukkit.plugin.Plugin plugin() {
        return JavaPlugin.getProvidingPlugin(BedrockBodyForm.class);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  ENTRADA: hoja del cuerpo (un botón por parte lesionada). Devuelve false si no es
    //  Bedrock o si Floodgate/Cumulus no están en runtime (-> BodyGUI usa el chat).
    // ─────────────────────────────────────────────────────────────────────────────
    public static boolean open(Player viewer, Player patient, BodyManager mgr) {
        if (!isBedrock(viewer)) return false;
        try {
            // Registramos quién mira a quién, igual que openChat/openOverview, para que los
            // subcomandos /cuerpo ver|tratar y los reaperturas resuelvan el paciente correcto.
            BodyGUI.VIEWING.put(viewer.getUniqueId(), patient.getUniqueId());
            BodyGUI.VIEWING_PART.remove(viewer.getUniqueId());

            boolean self = viewer.getUniqueId().equals(patient.getUniqueId());
            int course = mgr.courseOf(viewer);
            EnumMap<BodyPart, BodyManager.PartState> body = mgr.body(patient.getUniqueId());

            // Construye los botones SOLO de las partes con lesión (estado en el label).
            final List<BodyPart> injured = new ArrayList<>();
            final List<String> labels = new ArrayList<>();
            for (BodyPart part : ORDER) {
                BodyManager.PartState s = body.get(part);
                if (s == null || !s.any()) continue;
                injured.add(part);
                String cond = BodyGUI.primaryCondition(s);
                String estado = course >= 2 ? ((int) s.hp) + "/100" : hpWord(s.hp);
                labels.add(capitalize(part.display) + "\n" + tierWord(cond) + " · " + estado);
            }

            double pain = mgr.painOf(patient.getUniqueId());
            StringBuilder content = new StringBuilder();
            content.append(self ? "Tu cuerpo." : ("Paciente: " + ChatColor.stripColor(patient.getName()) + "."));
            if (course >= 1) content.append("\nDolor: ").append(painWord(pain)).append(".");
            if (injured.isEmpty()) {
                content.append("\n\nSin lesiones. Estas de una pieza.");
            } else if (course == 0) {
                content.append("\n\nSin formacion solo intuyes que esta malherido.");
            } else {
                content.append("\n\nToca una parte para verla o tratarla.");
            }

            Consumer<Integer> onButton = id -> {
                if (id == null || id < 0 || id >= injured.size()) return;
                BodyPart part = injured.get(id);
                // Reabrir la hoja de PARTE en el hilo principal (resuelve paciente por su UUID actual).
                Bukkit.getScheduler().runTask(plugin(), () -> {
                    if (!viewer.isOnline()) return;
                    Player pat = resolvePatient(viewer);
                    openPart(viewer, pat, part, mgr);
                });
            };

            return sendSimpleForm(viewer,
                    "FICHA MEDICA",
                    content.toString(),
                    labels,
                    onButton);
        } catch (Throwable t) {
            return false;   // cualquier fallo de reflexión -> BodyGUI usa el chat (sin regresión)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  HOJA DE PARTE: VER DETALLE (chat) / TRATAR (mismo backend) / VOLVER.
    // ─────────────────────────────────────────────────────────────────────────────
    public static void openPart(Player viewer, Player patient, BodyPart part, BodyManager mgr) {
        if (!isBedrock(viewer)) return;
        BodyGUI.VIEWING.put(viewer.getUniqueId(), patient.getUniqueId());
        BodyGUI.VIEWING_PART.put(viewer.getUniqueId(), part);
        int course = mgr.courseOf(viewer);
        BodyManager.PartState s = mgr.body(patient.getUniqueId()).get(part);
        boolean hasInjury = s != null && s.any();

        StringBuilder content = new StringBuilder();
        content.append(capitalize(part.display)).append("\n");
        if (!hasInjury) {
            content.append("Sin lesiones que tratar.");
        } else {
            String cond = BodyGUI.primaryCondition(s);
            content.append(tierWord(cond)).append(" · ")
                    .append(course >= 2 ? ((int) s.hp) + "/100" : hpWord(s.hp));
        }

        // Botones: el orden es FIJO para mapear la respuesta sin ambigüedad.
        //  0 = Ver detalle (chat) · 1 = Tratar (si procede) · 2 = Volver
        final List<String> labels = new ArrayList<>();
        labels.add("Ver detalle");
        final boolean canTreat = hasInjury && course >= 1;
        if (canTreat) labels.add("Tratar " + ChatColor.stripColor(part.display));
        labels.add("< Volver a la ficha");

        Consumer<Integer> onButton = id -> {
            if (id == null) return;
            int idxDetalle = 0;
            int idxTratar = canTreat ? 1 : -1;
            int idxVolver = canTreat ? 2 : 1;
            Bukkit.getScheduler().runTask(plugin(), () -> {
                if (!viewer.isOnline()) return;
                Player pat = resolvePatient(viewer);
                if (id == idxDetalle) {
                    // VER DETALLE: reusa el informe por CHAT (gradual por curso); luego reabre la hoja.
                    BodyGUI.chatDetail(viewer, pat, part, mgr);
                    openPart(viewer, pat, part, mgr);
                } else if (id == idxTratar) {
                    // TRATAR: EXACTAMENTE el mismo backend que el cofre/chat (puede abrir minijuego).
                    mgr.treat(viewer, pat, part);
                    // Si treat() abrió un canal (cerró inventario y dio una barra), NO reabrimos el form.
                    if (!mgr.isChanneling(viewer.getUniqueId())) open(viewer, pat, mgr);
                } else if (id == idxVolver) {
                    open(viewer, pat, mgr);
                }
            });
        };

        sendSimpleForm(viewer, "FICHA MEDICA", content.toString(), labels, onButton);
    }

    /** Resuelve el paciente actual desde el mapa VIEWING (o el propio viewer). */
    private static Player resolvePatient(Player viewer) {
        UUID pid = BodyGUI.VIEWING.get(viewer.getUniqueId());
        Player patient = pid != null ? Bukkit.getPlayer(pid) : viewer;
        return patient != null ? patient : viewer;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  SimpleForm de Cumulus por REFLEXIÓN + envío vía FloodgateApi (idéntico a MarketManager).
    // ─────────────────────────────────────────────────────────────────────────────
    private static boolean sendSimpleForm(Player p, String title, String content,
                                          List<String> buttons, Consumer<Integer> onButton) {
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
            for (String b : buttons) buttonM.invoke(builder, ChatColor.stripColor(b));

            java.util.function.Consumer<Object> handler = resp -> {
                try {
                    if (resp == null) return;   // cerró el menú
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

    /** True si el jugador entró por Bedrock (Floodgate). Reflexión-safe: false si Floodgate no está. */
    public static boolean isBedrock(Player player) {
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled("floodgate")) return false;
            Class<?> apiCls = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = apiCls.getMethod("getInstance").invoke(null);
            return (boolean) apiCls.getMethod("isFloodgatePlayer", UUID.class).invoke(api, player.getUniqueId());
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ─── etiquetas cortas (sin emojis: Bedrock no los pinta) ───
    private static String tierWord(String cond) {
        switch (tierOf(cond)) {
            case 3: return "CRITICO";
            case 4: return "INFECTADO";
            case 2: return "Serio";
            default: return "Leve";
        }
    }
    private static int tierOf(String cond) {
        switch (cond) {
            case "INFECCION": return 4;
            case "SANGRADO_GRAVE": case "FRACTURA_3": case "BALA_PROFUNDA": return 3;
            case "SANGRADO": case "BALA": case "CORTE_PROFUNDO": case "FRACTURA_2":
            case "QUEMADURA_2": case "ESGUINCE_2": case "INFECCION_RIESGO": case "CONTUSION_3": return 2;
            default: return 1;
        }
    }
    private static String hpWord(double hp) { return hp > 75 ? "buena" : hp > 50 ? "regular" : hp > 25 ? "mala" : "critica"; }
    private static String painWord(double pn) { return pn >= 70 ? "INSOPORTABLE" : pn >= 40 ? "fuerte" : pn >= 15 ? "molesto" : "controlado"; }
    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
