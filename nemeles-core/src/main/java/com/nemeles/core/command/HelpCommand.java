package com.nemeles.core.command;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * /ayuda — menu de ayuda navegable por categorias para NemelesRP "Bahia Negra".
 *
 * <p>Es el ONBOARDING central del servidor: con +80 comandos repartidos en ~30 modulos, un jugador
 * nuevo no tiene por donde empezar. Este comando agrupa todo en categorias claras y deja navegar.</p>
 *
 * <p><b>Crossplay sin dependencias nuevas.</b> La base (esta clase) compila contra SOLO Paper API:
 * <ul>
 *   <li><b>Java</b>: chat clicable (Spigot {@code TextComponent}) — clic en una categoria ejecuta
 *       {@code /ayuda &lt;cat&gt;}; hover muestra una pista.</li>
 *   <li><b>Bedrock</b>: el chat clicable TAMBIEN funciona (Geyser traduce los click-events a texto
 *       tocable), asi que el menu es usable en ambas plataformas desde el dia uno.</li>
 * </ul>
 * MEJORA OPCIONAL (ver {@link #tryOpenBedrockForm}): si en runtime estan presentes Floodgate + Cumulus,
 * a los jugadores Bedrock se les abre un {@code SimpleForm} nativo (botones tactiles) en vez del chat.
 * Se accede por REFLEXION, por eso esta clase compila aunque el modulo no dependa de Floodgate. Para
 * activarlo de forma "de primera" basta anadir Floodgate como {@code provided} al pom (ver entrega).</p>
 *
 * <p>Idioma: espanol latino neutro. Sin emojis hardcodeados (solo simbolos ASCII seguros como "»").</p>
 */
public final class HelpCommand implements CommandExecutor, TabCompleter {

    /** Una entrada de comando dentro de una categoria. */
    private record Entry(String usage, String desc) {}

    /** Una categoria del menu: id (para /ayuda &lt;id&gt;), titulo visible y sus comandos. */
    private record Category(String id, String title, String subtitle, List<Entry> entries) {}

    private final Plugin plugin;
    private final Map<String, Category> categories = new LinkedHashMap<>();

    public HelpCommand(Plugin plugin) {
        this.plugin = plugin;
        buildCatalog();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  CATALOGO — categorias y sus comandos (curado a mano desde los plugin.yml).
    //  Si anades un modulo nuevo, agrega aqui sus comandos: es la unica fuente del menu.
    // ─────────────────────────────────────────────────────────────────────────────
    private void buildCatalog() {
        add("inicio", "Primeros pasos / DNI", "Empieza aqui si eres nuevo",
                e("/dni", "Saca tu documento de identidad (RP)."),
                e("/identificarse [todos]", "Revela tu identidad a quien miras o a todos."),
                e("/identidades", "Ve a quien le revelaste tu identidad."),
                e("/me <accion>", "Accion de rol en 3a persona."),
                e("/do <descripcion>", "Describe el entorno o la escena."),
                e("/try <accion>", "Accion de rol con resultado al azar."),
                e("/phone", "Abre tu telefono (apps, banco, mapa, musica)."),
                e("/racha", "Recompensa por conectarte dias seguidos."));

        add("dinero", "Dinero y banco", "Efectivo, banco y dinero sucio",
                e("/pagar <jugador> <cantidad>", "Entrega efectivo en mano a alguien cercano."),
                e("/cajero [comision]", "Convierte dinero marcado en banco sin rastro."),
                e("/negocio <...>", "Crea y gestiona tu negocio (empleados, stock, lavado)."),
                e("/inmobiliaria", "Mira las propiedades en venta."),
                e("/propiedad <...>", "Compra, alquila o gestiona propiedades."),
                e("/casa", "Info / viaja a tu casa."),
                e("/deuda <...>", "Prestamos de calle: prestar, pagar, cobrar."),
                e("/casino <juego> <apuesta>", "Cara o cruz, dados y tragaperras."));

        add("trabajo", "Trabajo y habilidades", "Sube de nivel y gana dinero legal",
                e("/jobs", "Tus habilidades: progreso y ranking."),
                e("/empleo <...>", "Elige UN empleo (list, join, leave, info)."),
                e("/perk <elegir|info>", "Perks de habilidades (1 de 2 cada 10 niveles)."),
                e("/trabajo <...>", "Misiones de servicio con clientes NPC."),
                e("/carrera <...>", "Carreras publicas (policia, medico, mecanico, taxista)."),
                e("/servicio", "Entra o sal de servicio en tu carrera."),
                e("/reparar <jugador>", "(Mecanico) Repara el coche de un cliente."),
                e("/taxi <...>", "(Taxista) Taximetro: iniciar, cobrar, cancelar."));

        add("crimen", "Crimen y drogas", "La via rapida... y peligrosa",
                e("/weed <...>", "Marihuana: semilla, procesar, vender."),
                e("/mercadonegro <cat> <precio> <desc>", "Publica un trapicheo en los bajos fondos."),
                e("/contrato <...>", "Coge encargos con objetivos y recompensa."),
                e("/recompensa <...>", "Pon precio a una cabeza o cobra una."),
                e("/sobornar <policia> <cantidad>", "Ofrece un soborno para limpiar tu busqueda."),
                e("/calor", "Cuanto te vigila la ciudad ahora mismo."),
                e("/wanted", "Tu nivel de busqueda policial."));

        add("mafias", "Mafias y territorios", "Crea tu organizacion y domina la calle",
                e("/faccion <...>", "Mafias: crear, invitar, banco, home, permisos, aliados."),
                e("/turf <...>", "Ve y disputa territorios (atacar, mias, guerra)."),
                e("/reputacion [jugador]", "Tu notoriedad criminal y reputacion comunitaria."));

        add("policia", "Policia", "Solo para fuerzas del orden",
                e("/policia <...>", "Buscados, limpiar, avistar."),
                e("/mdt <...>", "Terminal: ficha, rastrear, fichar, codigo penal."),
                e("/esposar <jugador>", "Esposa a un derribado o buscado cercano."),
                e("/desesposar <jugador>", "Quita las esposas."),
                e("/meterpreso <jugador>", "Mete preso a un esposado (condena por estrellas)."),
                e("/carcel <jugador|liberar>", "Encarcela o libera a un jugador."),
                e("/soborno <aceptar|rechazar>", "Acepta o rechaza un soborno pendiente."));

        add("combate", "Combate y medico", "Tiroteos, heridas y reanimacion",
                e("/cuerpo [paciente]", "Ficha medica por partes del cuerpo."),
                e("/revivir", "Reanima al jugador derribado al que miras."),
                e("/emt [info]", "Estado del rol medico / brujula al derribado."),
                e("/medicina estudiar <nivel>", "Estudia cursos de medicina."),
                e("/rendirse", "Acelera tu muerte estando derribado."),
                e("/fugarse", "Intenta escapar de la carcel."),
                e("/stats [jugador]", "Estadisticas de combate y ranking."));

        add("vehiculos", "Vehiculos", "Tu garaje sobre ruedas",
                e("/vehiculo <...>", "Comprar, invocar, guardar, gasolina, lista."),
                e("/coche [guardar]", "Invoca o guarda tu vehiculo principal."),
                e("/garage [invocar <matricula>]", "Lista e invoca tus vehiculos."),
                e("/claxon", "Toca el claxon."));

        add("telefono", "Telefono", "Tu movil estilo GTA",
                e("/phone [item]", "Abre el telefono o reclama el item."),
                e("/msg <num|nombre>", "Abre o crea una conversacion."),
                e("/call <jugador|accept|deny|hangup>", "Llama por voz a otro jugador."),
                e("/music <...>", "App de musica / tu playlist personal."),
                e("/voz", "Habla con los NPCs por microfono."));

        add("rol", "Rol, chat y ciudad", "Vive el personaje y la ciudad viva",
                e("/ooc <mensaje>", "Habla fuera de personaje (local)."),
                e("/shout <mensaje>", "Grita para que te oigan mas lejos."),
                e("/hablar <mensaje>", "Habla con el NPC con el que conversas."),
                e("/relacion [nombre]", "Tu relacion (afinidad) con un NPC."),
                e("/periodico", "Recibe el periodico de la ciudad."),
                e("/alerta", "Estado de la alerta de ciudad (Verde/Roja...)."),
                e("/sed", "Tu nivel de sed (necesidades del personaje)."),
                e("/cosmetic <...>", "Tus cosmeticos: titulo, color, aura."),
                e("/pase", "Pase de temporada (battle pass)."),
                e("/creditos", "Tus creditos premium."));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  EJECUCION
    // ─────────────────────────────────────────────────────────────────────────────
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            // Consola: volcado plano de todo (sin clics).
            dumpPlainToConsole(sender);
            return true;
        }

        if (args.length == 0) {
            openMainMenu(player);
            return true;
        }

        String key = args[0].toLowerCase(Locale.ROOT);

        // /ayuda buscar <texto> — busca un comando por palabra (titulo, uso o descripcion).
        if (key.equals("buscar") || key.equals("busca") || key.equals("search") || key.equals("find")) {
            if (args.length < 2) {
                player.sendMessage(ChatColor.YELLOW + "[Ayuda] Uso: " + ChatColor.WHITE
                        + "/ayuda buscar <palabra>" + ChatColor.GRAY + "  (ej: /ayuda buscar carcel)");
                return true;
            }
            String query = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            doSearch(player, query);
            return true;
        }

        Category cat = categories.get(key);
        if (cat == null) {
            // No es una categoria conocida: en vez de un error seco, lo tratamos como busqueda.
            // Asi "/ayuda carcel" o "/ayuda vender" encuentran el comando directamente.
            String query = String.join(" ", args);
            doSearch(player, query);
            return true;
        }
        openCategory(player, cat);
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  BUSCADOR — escanea todas las entradas (uso + descripcion) y categorias por la
    //  palabra dada (sin acentos, sin mayusculas) y muestra coincidencias clicables.
    // ─────────────────────────────────────────────────────────────────────────────
    private void doSearch(Player player, String query) {
        String needle = normalize(query);
        if (needle.isEmpty()) {
            openMainMenu(player);
            return;
        }

        // Recolecta coincidencias: cada hit recuerda su categoria (para el "ver categoria") y la entrada.
        List<SearchHit> hits = new ArrayList<>();
        for (Category cat : categories.values()) {
            boolean catMatches = normalize(cat.title()).contains(needle)
                    || normalize(cat.subtitle()).contains(needle)
                    || normalize(cat.id()).contains(needle);
            for (Entry e : cat.entries()) {
                boolean entryMatches = normalize(e.usage()).contains(needle)
                        || normalize(e.desc()).contains(needle);
                if (entryMatches || catMatches) {
                    hits.add(new SearchHit(cat, e));
                }
            }
        }

        // Bedrock con Forms nativos → SimpleForm tactil con los resultados.
        if (tryOpenSearchForm(player, query, hits)) {
            return;
        }

        player.sendMessage("");
        if (hits.isEmpty()) {
            player.sendMessage(ChatColor.DARK_GREEN.toString() + ChatColor.BOLD + "  BUSQUEDA: "
                    + ChatColor.WHITE + query);
            player.sendMessage(ChatColor.GRAY + "  No encontre ningun comando con esa palabra.");
            player.sendMessage(ChatColor.DARK_GRAY + "  Prueba otra palabra, o mira las categorias:");
            TextComponent menu = new TextComponent("  " + ChatColor.YELLOW + "» Abrir el menu de ayuda");
            menu.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ayuda"));
            menu.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(ChatColor.GRAY + "Clic: /ayuda")));
            player.spigot().sendMessage(menu);
            player.sendMessage("");
            return;
        }

        int total = hits.size();
        int shown = Math.min(total, SEARCH_MAX);
        player.sendMessage(ChatColor.DARK_GREEN.toString() + ChatColor.BOLD + "  BUSQUEDA: "
                + ChatColor.WHITE + query + ChatColor.GRAY + "  (" + shown
                + (total > shown ? " de " + total : "") + " resultado" + (shown == 1 ? "" : "s") + ")");
        player.sendMessage("");

        for (int i = 0; i < shown; i++) {
            SearchHit hit = hits.get(i);
            Entry e = hit.entry();
            String base = baseCommand(e.usage());
            TextComponent line = new TextComponent("  " + ChatColor.AQUA + e.usage()
                    + ChatColor.DARK_GRAY + "  [" + ChatColor.GRAY + hit.category().title() + ChatColor.DARK_GRAY + "]");
            String hover = ChatColor.GRAY + e.desc() + "\n"
                    + ChatColor.DARK_GRAY + "Clic: escribe " + ChatColor.YELLOW + base;
            line.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(hover)));
            line.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, base + " "));
            player.spigot().sendMessage(line);
            player.sendMessage(ChatColor.GRAY + "     " + e.desc());
        }

        if (total > shown) {
            player.sendMessage("");
            player.sendMessage(ChatColor.DARK_GRAY + "  Hay mas resultados. Afina la palabra para ver menos.");
        }
        player.sendMessage("");
    }

    /** Un resultado de busqueda: la entrada y la categoria a la que pertenece. */
    private record SearchHit(Category category, Entry entry) {}

    /** Maximo de resultados de busqueda que mostramos de golpe (evita spamear el chat). */
    private static final int SEARCH_MAX = 12;

    /** Minusculas + sin acentos para que "carcel" encuentre "carcel" y "cárcel". */
    private static String normalize(String s) {
        if (s == null) return "";
        String lower = s.toLowerCase(Locale.ROOT);
        String decomposed = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").trim();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  MENU PRINCIPAL (lista de categorias)
    // ─────────────────────────────────────────────────────────────────────────────
    private void openMainMenu(Player player) {
        // Bedrock con Forms nativos disponibles → abre el SimpleForm tactil (mejora opcional).
        if (tryOpenBedrockForm(player, null)) {
            return;
        }

        player.sendMessage("");
        player.sendMessage(ChatColor.DARK_GREEN.toString() + ChatColor.BOLD
                + "  AYUDA DE BAHIA NEGRA");
        player.sendMessage(ChatColor.GRAY + "  Toca una categoria para ver sus comandos:");
        player.sendMessage("");

        for (Category cat : categories.values()) {
            TextComponent line = new TextComponent(" " + ChatColor.GREEN + "» "
                    + ChatColor.WHITE + cat.title());
            String hover = ChatColor.GRAY + cat.subtitle() + "\n"
                    + ChatColor.DARK_GRAY + "Clic: " + ChatColor.YELLOW + "/ayuda " + cat.id();
            line.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(hover)));
            line.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ayuda " + cat.id()));
            player.spigot().sendMessage(line);
        }

        player.sendMessage("");
        TextComponent search = new TextComponent(" " + ChatColor.YELLOW + "» "
                + ChatColor.GOLD + "Buscar un comando por palabra");
        String searchHover = ChatColor.GRAY + "Ej: /ayuda buscar carcel\n"
                + ChatColor.DARK_GRAY + "Clic: escribe " + ChatColor.YELLOW + "/ayuda buscar ";
        search.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(searchHover)));
        search.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/ayuda buscar "));
        player.spigot().sendMessage(search);

        player.sendMessage("");
        player.sendMessage(ChatColor.DARK_GRAY + "  Consejo: escribe " + ChatColor.YELLOW + "/ayuda <categoria>"
                + ChatColor.DARK_GRAY + " o " + ChatColor.YELLOW + "/ayuda buscar <palabra>" + ChatColor.DARK_GRAY + ".");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  DETALLE DE UNA CATEGORIA
    // ─────────────────────────────────────────────────────────────────────────────
    private void openCategory(Player player, Category cat) {
        if (tryOpenBedrockForm(player, cat)) {
            return;
        }

        player.sendMessage("");
        player.sendMessage(ChatColor.DARK_GREEN.toString() + ChatColor.BOLD + "  " + cat.title().toUpperCase(Locale.ROOT));
        player.sendMessage(ChatColor.GRAY + "  " + cat.subtitle());
        player.sendMessage("");

        for (Entry e : cat.entries()) {
            // El comando es clicable: deja /comando escrito en el chat (SUGGEST), listo para completar.
            String base = baseCommand(e.usage());
            TextComponent line = new TextComponent("  " + ChatColor.AQUA + e.usage());
            String hover = ChatColor.GRAY + e.desc() + "\n"
                    + ChatColor.DARK_GRAY + "Clic: escribe " + ChatColor.YELLOW + base;
            line.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(hover)));
            line.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, base + " "));
            player.spigot().sendMessage(line);

            player.sendMessage(ChatColor.GRAY + "     " + e.desc());
        }

        player.sendMessage("");
        TextComponent back = new TextComponent("  " + ChatColor.YELLOW + "« Volver al menu de ayuda");
        back.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ayuda"));
        back.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(ChatColor.GRAY + "Clic: /ayuda")));
        player.spigot().sendMessage(back);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  MEJORA OPCIONAL — Form nativo de Bedrock (SimpleForm) por REFLEXION.
    //
    //  Esta clase NO depende de Floodgate/Cumulus en tiempo de compilacion. Si esas clases
    //  existen en runtime (server con Geyser/Floodgate, igual que el plugin del telefono), se
    //  abre un SimpleForm tactil para jugadores Bedrock. Si no, devuelve false y se usa el chat.
    //
    //  Para reemplazar este puente reflexivo por codigo directo (mas legible), anade Floodgate
    //  como dependencia provided al pom del modulo y copia el patron de BedrockPhone:
    //      SimpleForm.builder().title(...).button(...).validResultHandler(resp -> ...).build();
    //      FloodgateApi.getInstance().sendForm(uuid, form);
    // ─────────────────────────────────────────────────────────────────────────────
    private boolean tryOpenBedrockForm(Player player, Category cat) {
        if (!isBedrock(player)) {
            return false;
        }
        try {
            Class<?> simpleFormCls = Class.forName("org.geysermc.cumulus.form.SimpleForm");
            Class<?> builderCls = Class.forName("org.geysermc.cumulus.form.SimpleForm$Builder");
            Class<?> respHandlerCls = Class.forName("java.util.function.Consumer");

            Object builder = simpleFormCls.getMethod("builder").invoke(null);
            var titleM = builderCls.getMethod("title", String.class);
            var contentM = builderCls.getMethod("content", String.class);
            var buttonM = builderCls.getMethod("button", String.class);
            var buildM = builderCls.getMethod("build");
            var handlerM = builderCls.getMethod("validResultHandler", java.util.function.Consumer.class);

            final List<Runnable> actions = new ArrayList<>();

            if (cat == null) {
                titleM.invoke(builder, "Ayuda de Bahia Negra");
                contentM.invoke(builder, ChatColor.stripColor("Elige una categoria:"));
                for (Category c : categories.values()) {
                    buttonM.invoke(builder, c.title() + "\n" + c.subtitle());
                    final Category target = c;
                    actions.add(() -> openCategory(player, target));
                }
            } else {
                titleM.invoke(builder, cat.title());
                StringBuilder body = new StringBuilder();
                for (Entry e : cat.entries()) {
                    body.append(e.usage()).append("\n").append(e.desc()).append("\n\n");
                }
                contentM.invoke(builder, body.toString().trim());
                buttonM.invoke(builder, "Volver al menu de ayuda");
                actions.add(() -> openMainMenu(player));
            }

            // Consumer<SimpleFormResponse>: leemos clickedButtonId() por reflexion y ejecutamos la accion.
            java.util.function.Consumer<Object> handler = resp -> {
                try {
                    if (resp == null) return; // cerro la form
                    int id = (int) resp.getClass().getMethod("clickedButtonId").invoke(resp);
                    if (id >= 0 && id < actions.size()) {
                        Bukkit.getScheduler().runTask(plugin, actions.get(id));
                    }
                } catch (Throwable ignored) {
                    // Si algo falla, no rompemos nada: el jugador simplemente cierra la form.
                }
            };
            handlerM.invoke(builder, handler);

            Object form = buildM.invoke(builder);

            Class<?> floodgateApiCls = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = floodgateApiCls.getMethod("getInstance").invoke(null);
            Class<?> formIface = Class.forName("org.geysermc.cumulus.form.Form");
            floodgateApiCls.getMethod("sendForm", UUID.class, formIface)
                    .invoke(api, player.getUniqueId(), form);
            return true;
        } catch (Throwable t) {
            // Floodgate/Cumulus no presentes o API distinta: caemos al menu de chat (que ya funciona en Bedrock).
            return false;
        }
    }

    /**
     * Form nativo de Bedrock para los RESULTADOS de busqueda (mismo patron reflexivo que
     * {@link #tryOpenBedrockForm}). Un boton por comando encontrado: al tocarlo abre la
     * categoria que lo contiene (en Bedrock no se puede "pre-escribir" un comando como en Java).
     * Devuelve false si el jugador es Java o Floodgate/Cumulus no estan: el caller cae al chat.
     */
    private boolean tryOpenSearchForm(Player player, String query, List<SearchHit> hits) {
        if (!isBedrock(player)) {
            return false;
        }
        try {
            Class<?> simpleFormCls = Class.forName("org.geysermc.cumulus.form.SimpleForm");
            Class<?> builderCls = Class.forName("org.geysermc.cumulus.form.SimpleForm$Builder");

            Object builder = simpleFormCls.getMethod("builder").invoke(null);
            var titleM = builderCls.getMethod("title", String.class);
            var contentM = builderCls.getMethod("content", String.class);
            var buttonM = builderCls.getMethod("button", String.class);
            var buildM = builderCls.getMethod("build");
            var handlerM = builderCls.getMethod("validResultHandler", java.util.function.Consumer.class);

            titleM.invoke(builder, "Buscar: " + query);

            final List<Runnable> actions = new ArrayList<>();
            if (hits.isEmpty()) {
                contentM.invoke(builder, "No encontre ningun comando con esa palabra.");
                buttonM.invoke(builder, "Volver al menu de ayuda");
                actions.add(() -> openMainMenu(player));
            } else {
                int shown = Math.min(hits.size(), SEARCH_MAX);
                contentM.invoke(builder, "Toca un comando para ver su categoria:");
                for (int i = 0; i < shown; i++) {
                    SearchHit hit = hits.get(i);
                    buttonM.invoke(builder, hit.entry().usage() + "\n" + hit.category().title());
                    final Category target = hit.category();
                    actions.add(() -> openCategory(player, target));
                }
                buttonM.invoke(builder, "Volver al menu de ayuda");
                actions.add(() -> openMainMenu(player));
            }

            java.util.function.Consumer<Object> handler = resp -> {
                try {
                    if (resp == null) return;
                    int id = (int) resp.getClass().getMethod("clickedButtonId").invoke(resp);
                    if (id >= 0 && id < actions.size()) {
                        Bukkit.getScheduler().runTask(plugin, actions.get(id));
                    }
                } catch (Throwable ignored) {
                }
            };
            handlerM.invoke(builder, handler);

            Object form = buildM.invoke(builder);
            Class<?> floodgateApiCls = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = floodgateApiCls.getMethod("getInstance").invoke(null);
            Class<?> formIface = Class.forName("org.geysermc.cumulus.form.Form");
            floodgateApiCls.getMethod("sendForm", UUID.class, formIface)
                    .invoke(api, player.getUniqueId(), form);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** True si el jugador entro por Bedrock. Reflexion-safe: false si Floodgate no esta. */
    private boolean isBedrock(Player player) {
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled("floodgate")) return false;
            Class<?> apiCls = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = apiCls.getMethod("getInstance").invoke(null);
            return (boolean) apiCls.getMethod("isFloodgatePlayer", UUID.class)
                    .invoke(api, player.getUniqueId());
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  TAB-COMPLETE: sugiere los ids de categoria.
    // ─────────────────────────────────────────────────────────────────────────────
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String id : categories.keySet()) {
            if (id.startsWith(prefix)) out.add(id);
        }
        if ("buscar".startsWith(prefix)) out.add("buscar");
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Volcado para consola (sin componentes clicables).
    // ─────────────────────────────────────────────────────────────────────────────
    private void dumpPlainToConsole(CommandSender sender) {
        sender.sendMessage("=== AYUDA DE BAHIA NEGRA ===");
        for (Category cat : categories.values()) {
            sender.sendMessage("");
            sender.sendMessage("[" + cat.id() + "] " + cat.title() + " - " + cat.subtitle());
            for (Entry e : cat.entries()) {
                sender.sendMessage("  " + e.usage() + "  ->  " + e.desc());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Helpers de construccion del catalogo.
    // ─────────────────────────────────────────────────────────────────────────────
    private void add(String id, String title, String subtitle, Entry... entries) {
        categories.put(id, new Category(id, title, subtitle, Arrays.asList(entries)));
    }

    private static Entry e(String usage, String desc) {
        return new Entry(usage, desc);
    }

    /** Primer token de un uso ("/faccion <...>" -> "/faccion") para los clics SUGGEST. */
    private static String baseCommand(String usage) {
        int sp = usage.indexOf(' ');
        return (sp < 0) ? usage : usage.substring(0, sp);
    }

    /** Builder de ayuda (no se usa internamente, pero permite que otros modulos
     *  reutilicen el ComponentBuilder si quisieran extender el menu). */
    @SuppressWarnings("unused")
    private static TextComponent legacyLine(String text) {
        return new TextComponent(new ComponentBuilder(text).create());
    }
}
