package com.nemeles.npcai;

import com.nemeles.core.api.NemelesApi;
import com.nemeles.core.api.economy.MoneyType;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * CADENA DE LA COCAINA (mercado nomada, pedido por el usuario):
 *   1. VENDEDOR ambulante (El Tuerto / La Gitana): te vende HOJA DE COCA por dinero SUCIO.
 *   2. PROCESADOR clandestino (El Quimico / El Sordo): 8 hojas + tarifa = 1 GRAMO.
 *   3. COMPRADOR NOMADA (Don Anselmo / La Sombra): SOLO UNO esta activo cada dia (rotacion);
 *      el resto te suelta una PISTA vaga de donde anda el que compra hoy. Vender da SUCIO,
 *      calor y riesgo de que la Unidad Costa se entere. Mas riesgo y margen que la maria.
 * Los NPC se crean con esos NOMBRES (Citizens); los textos son de Qwen (equipo de IAs).
 */
public final class CocaChainManager {

    private final Plugin plugin;
    private final NamespacedKey cocaKey;

    /** Oferta del comprador: precio fijado + regateo de UN SOLO intento (exito o fallo, se consume). */
    private static final class Offer { long at; double price; boolean haggled; double market = 1.0; }
    private final Map<UUID, Offer> offers = new ConcurrentHashMap<>();
    private final Map<UUID, Long> busy = new ConcurrentHashMap<>();      // anti doble-clic durante cobros async

    private final double hojaPrice, procesoFee, gramoBase;
    private final int hojasPorGramo;

    // ── limites diarios y riesgo (balance ChatGPT r23): reset a las 6:00 ──
    private static final class DayState { long day; int gramos; int hojas; }
    private final Map<UUID, DayState> daily = new ConcurrentHashMap<>();
    private final Map<UUID, Long> ventaCd = new ConcurrentHashMap<>();      // cooldown 45s del comprador
    private final Map<UUID, Long> roboCd = new ConcurrentHashMap<>();       // cooldown 90min del evento ROBO
    private long stockDay = -1;
    private int stockHojas;                                                  // stock GLOBAL diario del vendedor

    private final int maxGramosDia, maxHojasDiaJugador, stockHojasDia, maxGramosVenta;

    private static long dayIndex() { return (System.currentTimeMillis() / 1000L - 6 * 3600L) / 86400L; }

    private DayState day(UUID id) {
        long d = dayIndex();
        return daily.compute(id, (k, v) -> (v == null || v.day != d) ? newDay(d) : v);
    }

    private static DayState newDay(long d) { DayState s = new DayState(); s.day = d; return s; }

    private static final List<String> VENDEDORES = List.of("el tuerto", "la gitana");
    private static final List<String> PROCESADORES = List.of("el químico", "el quimico", "el sordo");
    private static final List<String> COMPRADORES = List.of("don anselmo", "la sombra");

    private static final String[] SALUDO_VENDEDOR = {
            "¿Buscas hoja fresca? Tengo la mejor del Levante, recién llegada y sin preguntar de dónde.",
            "Acércate, pero sin tonterías. La hoja está limpia y el precio es justo si no eres de la pasma.",
            "¿Necesitas materia prima? Tengo hoja de coca de primera, cruda pero con mucho potencial.",
            "No mires tanto, que espantas la suerte. ¿Vas a comprar hoja o solo vienes a perder el tiempo?"};
    private static final String[] SALUDO_PROCESADOR = {
            "Pasa y cierra la puerta. Los químicos están calientes y no quiero visitas de la Unidad Costa.",
            "Traes la hoja y los químicos, yo hago la magia. Pero no toques nada o volamos los dos.",
            "El proceso es delicado. Dame la materia prima y espera a que salga el polvo blanco.",
            "Aquí no se habla del pasado. Deja la hoja, paga los químicos y llévate tu producto terminado."};
    private static final String[] SALUDO_COMPRADOR = {
            "Llegas tarde. Espero que traigas los gramos que prometiste, porque mi paciencia es corta.",
            "Bien, bien. Muéstrame la mercancía. Si es pura, el dinero es tuyo al instante.",
            "No me mires a la cara. Deja los gramos en la mesa y coge el sobre. Nos vemos donde toque mañana.",
            "¿Eres tú el que trae el polvo? Vamos, no tenemos toda la noche. La Unidad Costa ronda por aquí."};
    private static final String[] PISTAS = {
            "Lo vieron merodeando por los muelles viejos justo cuando el sol se ponía, fumando un puro caro.",
            "Dicen que hoy se esconde en las ruinas de El Erial, esperando a que caiga la noche para hacer tratos.",
            "Un chivato dice que lo oyeron negociar cerca de la iglesia abandonada de Los Cerros al amanecer.",
            "Lo han visto aparcar un coche negro sin matrículas cerca de la entrada del puerto, mirando el reloj.",
            "Rumor: está en la zona industrial, entre los contenedores oxidados, esperando a un socio de confianza.",
            "Alguien dice que hoy se refugia en la trastienda de un bar de mala muerte en el límite del barrio.",
            "Lo vieron caminando por la playa desierta, dejando huellas que la marea se llevó.",
            "Cuentan que se mueve por los tejados de la zona vieja, observando quién entra y quién sale.",
            "Un vagabundo asegura que lo vio bajo el puente del río, contando fajos a la luz de la luna."};
    private static final String[] RIESGO = {
            "Cuidado, la Unidad Costa ha puesto un control aleatorio en la carretera principal.",
            "Se rumorea que hay un coche patrulla sin luces vigilando la entrada del barrio. Sigilo.",
            "La pasma está haciendo redadas en los muelles. Si llevas mercancía, busca otra ruta.",
            "He visto a dos agentes de la Unidad Costa merodeando cerca. Mejor abortar.",
            "Dicen que la Unidad Costa tiene un soplón en el mercado. No hables de negocios en público.",
            "Alerta: hay un helicóptero de la Unidad Costa dando vueltas. Quédate escondido."};

    public CocaChainManager(Plugin plugin) {
        this.plugin = plugin;
        this.cocaKey = new NamespacedKey(plugin, "coca_item");
        // defaults = balance de ChatGPT (r23): coste/gramo $680 SUCIO, venta $1450 -> margen ~2.5x la maria
        this.hojaPrice = plugin.getConfig().getDouble("coca.hoja-price", 70.0);
        this.procesoFee = plugin.getConfig().getDouble("coca.proceso-fee", 120.0);
        this.gramoBase = plugin.getConfig().getDouble("coca.gramo-price", 1450.0);
        this.hojasPorGramo = Math.max(1, plugin.getConfig().getInt("coca.hojas-por-gramo", 8));
        this.maxGramosDia = plugin.getConfig().getInt("coca.max-gramos-dia", 24);
        this.maxHojasDiaJugador = plugin.getConfig().getInt("coca.max-hojas-dia-jugador", 160);
        this.stockHojasDia = plugin.getConfig().getInt("coca.stock-hojas-dia", 320);
        this.maxGramosVenta = plugin.getConfig().getInt("coca.max-gramos-por-venta", 6);
    }

    // ─── items ───────────────────────────────────────────────
    private ItemStack hoja(int n) { return item("hoja", Material.PAPER, ChatColor.DARK_GREEN + "Hoja de coca", n,
            "Cruda. Un procesador sabría qué hacer con esto.", 4361); }
    private ItemStack gramo(int n) { return item("gramo", Material.GUNPOWDER, ChatColor.WHITE + "Gramo de coca", n,
            "Polvo blanco. Solo el comprador nómada paga lo que vale.", 4362); }

    private ItemStack item(String id, Material mat, String name, int n, String loreLine, int cmd) {
        ItemStack it = new ItemStack(mat, Math.max(1, n));
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(name);
        m.setLore(List.of(ChatColor.GRAY + loreLine, ChatColor.DARK_GRAY + "Contrabando — la Costa no perdona"));
        if (cmd > 0) m.setCustomModelData(cmd);   // textura propia en Java (pack); Bedrock ve el item base
        m.getPersistentDataContainer().set(cocaKey, PersistentDataType.STRING, id);
        it.setItemMeta(m);
        return it;
    }

    private String idOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(cocaKey, PersistentDataType.STRING);
    }

    private int countHojas(Player p) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) if ("hoja".equals(idOf(it))) n += it.getAmount();
        return n;
    }

    private void removeHojas(Player p, int n) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize() && n > 0; i++) {
            ItemStack it = inv.getItem(i);
            if (!"hoja".equals(idOf(it))) continue;
            int take = Math.min(n, it.getAmount());
            it.setAmount(it.getAmount() - take);
            n -= take;
        }
    }

    // ─── interaccion (la llama CitizensListener ANTES de la charla) ──
    /** true si el NPC era de la cadena de la coca y ya se gestionó el clic. */
    public boolean tryInteract(Player p, Entity npc) {
        String raw = npc.getCustomName() != null ? npc.getCustomName() : npc.getName();
        if (raw == null) return false;
        String name = ChatColor.stripColor(raw).toLowerCase(Locale.ROOT).trim();
        if (VENDEDORES.contains(name)) { vendedor(p, name); return true; }
        if (PROCESADORES.contains(name)) { procesador(p, name); return true; }
        int idx = COMPRADORES.indexOf(name);
        if (idx >= 0) { comprador(p, name, idx); return true; }
        return false;
    }

    /** Anti doble-clic: true si hay un cobro async en vuelo para este jugador (mismo patron que el hospital). */
    private boolean isBusy(Player p) {
        Long b = busy.get(p.getUniqueId());
        if (b != null && b > System.currentTimeMillis()) return true;
        busy.put(p.getUniqueId(), System.currentTimeMillis() + 5000L);
        return false;
    }

    private void unbusy(Player p) { busy.remove(p.getUniqueId()); }

    /** Devuelve dinero SUCIO (reversa de una operacion que no pudo completarse). */
    private void refund(UUID id, double amount, String reason) {
        try { NemelesApi.economy().deposit(id, MoneyType.SUCIO, BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP), reason); }
        catch (Throwable ignored) { }
    }

    private void vendedor(Player p, String name) {
        if (ThreadLocalRandom.current().nextDouble() < 0.30) say(p, name, pick(SALUDO_VENDEDOR));
        // stock global diario + tope por jugador (anti-inflacion, balance r23)
        long d = dayIndex();
        if (stockDay != d) { stockDay = d; stockHojas = stockHojasDia; }
        if (stockHojas <= 0) { say(p, name, "Se acabó la hoja por hoy. La mula no ha llegado... vuelve mañana."); return; }
        DayState ds = day(p.getUniqueId());
        if (ds.hojas >= maxHojasDiaJugador) { say(p, name, "A ti ya te he vendido bastante hoy. No me seas ansias, que llamas la atención."); return; }
        if (isBusy(p)) return;
        // contadores OPTIMISTAS (sync, antes del cobro): el doble-clic en vuelo ya no salta los topes
        stockHojas--;
        ds.hojas++;
        NemelesApi.economy().withdraw(p.getUniqueId(), MoneyType.SUCIO, BigDecimal.valueOf(hojaPrice), "coca:hoja")
                .whenComplete((res, err) -> org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                    unbusy(p);
                    if (err != null || res == null || !res.success()) {
                        stockHojas++;
                        day(p.getUniqueId()).hojas--;
                        if (err == null && p.isOnline()) {
                            say(p, name, "Esto se paga con dinero de la CALLE ($" + fmt(hojaPrice) + " sucios la hoja). Vuelve cuando lo tengas.");
                        }
                        return;
                    }
                    if (!p.isOnline()) { refund(p.getUniqueId(), hojaPrice, "coca:hoja:refund-offline"); return; }
                    p.getInventory().addItem(hoja(1)).values().forEach(left -> p.getWorld().dropItem(p.getLocation(), left));
                    p.sendMessage(ChatColor.DARK_GREEN + "+1 hoja de coca " + ChatColor.GRAY + "(-$" + fmt(hojaPrice) + " SUCIO)");
                    if (ThreadLocalRandom.current().nextDouble() < 0.15) say(p, name, pick(RIESGO));
                }));
    }

    private void procesador(Player p, String name) {
        DayState ds = day(p.getUniqueId());
        if (ds.gramos >= maxGramosDia) {
            say(p, name, "Hoy ya no cocino más para ti. La olla necesita descansar... y yo no quiero a la Costa oliendo el humo.");
            return;
        }
        int hojas = countHojas(p);
        if (hojas < hojasPorGramo) {
            say(p, name, pick(SALUDO_PROCESADOR));
            p.sendMessage(ChatColor.GRAY + "(necesitas " + hojasPorGramo + " hojas de coca + $" + fmt(procesoFee)
                    + " SUCIO por gramo — llevas " + hojas + ")");
            return;
        }
        if (isBusy(p)) return;
        // las hojas se consumen YA, en el hilo principal: ni doble-clic ni soltar-las-hojas
        // durante el roundtrip de BD pueden tragarse la tarifa o duplicar nada
        removeHojas(p, hojasPorGramo);
        ds.gramos++;
        NemelesApi.economy().withdraw(p.getUniqueId(), MoneyType.SUCIO, BigDecimal.valueOf(procesoFee), "coca:proceso")
                .whenComplete((res, err) -> org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                    unbusy(p);
                    if (err != null || res == null || !res.success()) {
                        day(p.getUniqueId()).gramos--;
                        if (p.isOnline()) {
                            p.getInventory().addItem(hoja(hojasPorGramo)).values()
                                    .forEach(left -> p.getWorld().dropItem(p.getLocation(), left));
                            if (err == null) say(p, name, "Los químicos no son gratis: $" + fmt(procesoFee)
                                    + " SUCIOS por cocción. Sin pasta no hay magia. Toma tus hojas.");
                        } else {
                            // offline y sin cobro: las hojas no se pueden devolver al inventario -> reversa en metalico
                            refund(p.getUniqueId(), hojasPorGramo * hojaPrice, "coca:proceso:refund-offline");
                        }
                        return;
                    }
                    if (!p.isOnline()) {
                        // pago hecho pero no se puede entregar el gramo: reversa COMPLETA (tarifa + valor de las hojas)
                        refund(p.getUniqueId(), procesoFee + hojasPorGramo * hojaPrice, "coca:proceso:refund-offline");
                        day(p.getUniqueId()).gramos--;
                        return;
                    }
                    p.getInventory().addItem(gramo(1)).values().forEach(left -> p.getWorld().dropItem(p.getLocation(), left));
                    say(p, name, "Listo. Polvo blanco de primera. Ahora búscate al que COMPRA hoy... si lo encuentras.");
                    p.sendMessage(ChatColor.WHITE + "+1 gramo de coca " + ChatColor.GRAY + "(-" + hojasPorGramo + " hojas, -$" + fmt(procesoFee) + ")");
                    try { NemelesApi.heat().onDrugSale(p.getUniqueId(), 1); } catch (Throwable ignored) { }
                }));
    }

    private void comprador(Player p, String name, int idx) {
        int activo = (int) ((System.currentTimeMillis() / 86_400_000L) % COMPRADORES.size());
        if (idx != activo) {
            // hoy NO compra: te suelta una PISTA vaga del que si (mercado nomada)
            say(p, name, "Hoy yo no muevo género. " + pick(PISTAS));
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!"gramo".equals(idOf(hand))) {
            say(p, name, pick(SALUDO_COMPRADOR));
            p.sendMessage(ChatColor.GRAY + "(ven con los GRAMOS en la mano: clic = oferta, agachado+clic = cerrar · máx "
                    + maxGramosVenta + " por trato)");
            return;
        }
        // el comprador NO es un cajero: ni buscados, ni jugadores al rojo vivo, ni ventas en rafaga
        try {
            if (NemelesApi.wanted().getStars(p.getUniqueId()) >= 4) {
                say(p, name, "¿Tú? Sales en todos los carteles de la Costa. Ni me mires: traes a la pasma pegada a los talones.");
                return;
            }
        } catch (Throwable ignored) { }
        try {
            if (NemelesApi.heat().playerHeat(p.getUniqueId()) >= 90) {
                say(p, name, "Estás que ardes, chaval. Media ciudad habla de ti. Enfríate unos días y luego hablamos de negocios.");
                return;
            }
        } catch (Throwable ignored) { }
        Long vcd = ventaCd.get(p.getUniqueId());
        if (vcd != null && vcd > System.currentTimeMillis()) {
            say(p, name, "Despacio. Acabamos de cerrar un trato; espera un momento, que las prisas huelen a trampa.");
            return;
        }
        int grams = Math.min(hand.getAmount(), maxGramosVenta);
        long now = System.currentTimeMillis();
        Offer of = offers.get(p.getUniqueId());
        if (of == null || now - of.at > 25_000L) {
            of = new Offer();
            of.at = now;
            // PRECIO DINÁMICO: el mercado server-wide de coca satura con cada gramo vendido hoy
            double market = 1.0;
            try { market = NemelesApi.heat().priceMultiplier("coca"); } catch (Throwable ignored) { }
            of.market = market;
            of.price = gramoBase * market * (1.0 + ThreadLocalRandom.current().nextDouble(-0.10, 0.10));
            offers.put(p.getUniqueId(), of);
            say(p, name, "Te doy $" + fmt(of.price) + " por gramo. " + grams + " gramos... $" + fmt(of.price * grams) + ". Agáchate y cierra, o lárgate.");
            if (market < 0.85) {
                say(p, name, "El polvo está barato esta semana: hay demasiado en la calle. No es contra ti, son negocios.");
            }
            return;
        }
        if (!p.isSneaking()) {
            // regateo de UN SOLO intento: exito o fallo, se CONSUME (y el precio tiene tope duro)
            if (of.haggled) {
                say(p, name, "Ya regateaste. El precio es el precio: agáchate y cierra, o lárgate.");
                return;
            }
            of.haggled = true;
            if (ThreadLocalRandom.current().nextDouble() < 0.5) {
                // el tope del regateo respeta el mercado del dia (no se salta la saturacion)
                of.price = Math.min(gramoBase * of.market * 1.18,
                        of.price * (1.0 + ThreadLocalRandom.current().nextDouble(0.08, 0.18)));
                say(p, name, "Vale, vale, me gusta tu estilo. $" + fmt(of.price) + " por gramo, y que no se entere nadie.");
            } else {
                say(p, name, "Aquí no regateamos como en un mercado de verduras. El precio es el precio, amigo.");
            }
            return;
        }
        // cierre
        if (isBusy(p)) return;
        offers.remove(p.getUniqueId());
        double price = of.price;
        double total = price * grams;
        hand.setAmount(hand.getAmount() - grams);   // solo consume lo vendido (tope por trato)
        ventaCd.put(p.getUniqueId(), System.currentTimeMillis() + 45_000L);

        // EVENTO RARO (balance r23): en tratos de 3g+ el comprador puede jugartela (cd 90 min)
        Long rcd = roboCd.get(p.getUniqueId());
        if (grams >= 3 && (rcd == null || rcd < System.currentTimeMillis())
                && ThreadLocalRandom.current().nextDouble() < 0.035) {
            roboCd.put(p.getUniqueId(), System.currentTimeMillis() + 90 * 60_000L);
            boolean fakeBills = ThreadLocalRandom.current().nextDouble() < 0.65;
            if (fakeBills) {
                double paid = total * 0.35;
                NemelesApi.economy().deposit(p.getUniqueId(), MoneyType.SUCIO,
                        BigDecimal.valueOf(paid).setScale(2, RoundingMode.HALF_UP), "coca:venta:falsos")
                        .whenComplete((r2, e2) -> org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                            unbusy(p);
                            if (p.isOnline()) p.sendMessage(ChatColor.DARK_RED
                                    + "Cuentas el fajo en el callejón... la mitad son BILLETES FALSOS. "
                                    + ChatColor.RED + "Solo $" + fmt(paid) + " son de verdad. Y el tipo ya no está.");
                        }));
            } else {
                unbusy(p);
                p.sendMessage(ChatColor.DARK_RED + "El comprador agarra el género, te mira... y ECHA A CORRER. "
                        + ChatColor.RED + "Te ha robado " + grams + " gramo(s). En este negocio no hay denuncias.");
            }
            // el género igual salió a la calle: satura el mercado de coca (server-wide) + calor extra
            try {
                NemelesApi.heat().onDrugSale(p.getUniqueId(), "coca", grams);
                NemelesApi.heat().onDrugSale(p.getUniqueId(), grams * 2);   // la coca "quema" más que la maría
            } catch (Throwable ignored) { }
            return;
        }

        NemelesApi.economy().deposit(p.getUniqueId(), MoneyType.SUCIO, BigDecimal.valueOf(total).setScale(2, RoundingMode.HALF_UP), "coca:venta")
                .whenComplete((res, err) -> org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                    unbusy(p);
                    if (err != null || res == null || !res.success()) {
                        // el dinero NO llego: devolver los gramos (o dejarlos caer si no caben / esta offline)
                        if (p.isOnline()) {
                            p.getInventory().addItem(gramo(grams)).values()
                                    .forEach(left -> p.getWorld().dropItem(p.getLocation(), left));
                            p.sendMessage(ChatColor.RED + "El trato se tuerce en el último segundo. Recuperas tu género.");
                        } else {
                            p.getWorld().dropItem(p.getLocation(), gramo(grams));
                        }
                        return;
                    }
                    if (!p.isOnline()) return;   // pagado; los mensajes/efectos ya no aplican
                    say(p, name, "Un placer. Mañana estaré... donde tenga que estar. " + ChatColor.GREEN + "+$" + fmt(total) + " SUCIO");
                    // PEAJE territorial: vender en zona de otra mafia paga al dueno (mismo hook que la maria)
                    try {
                        long toll = NemelesApi.territories().applyDrugTax(p.getUniqueId(), p.getLocation(),
                                Math.round(total * 100));
                        if (toll > 0) p.sendMessage(ChatColor.GRAY + "Esta esquina tiene dueño: $"
                                + fmt(toll / 100.0) + " del trato van a su bolsillo.");
                    } catch (Throwable ignored) { }
                    // SATURA el mercado de coca por los gramos reales vendidos + calor extra (la coca quema más)
                    try {
                        NemelesApi.heat().onDrugSale(p.getUniqueId(), "coca", grams);
                        NemelesApi.heat().onDrugSale(p.getUniqueId(), grams * 2);
                    } catch (Throwable ignored) { }
                    if (ThreadLocalRandom.current().nextDouble() < 0.10 * grams) {
                        try { NemelesApi.wanted().addCrime(p.getUniqueId(), 8, "coca:reported", p.getLocation(), false); } catch (Throwable ignored) { }
                        p.sendMessage(ChatColor.RED + "Un soplón te ha visto cerrar el trato... la Unidad Costa pregunta por ti.");
                    }
                }));
    }

    private void say(Player p, String npcName, String text) {
        String pretty = npcName.substring(0, 1).toUpperCase(Locale.ROOT) + npcName.substring(1);
        p.sendMessage(ChatColor.GRAY + pretty + ChatColor.DARK_GRAY + " ▏ " + ChatColor.WHITE + text);
    }

    private static String pick(String[] arr) { return arr[ThreadLocalRandom.current().nextInt(arr.length)]; }
    private static String fmt(double v) { return BigDecimal.valueOf(v).setScale(0, RoundingMode.HALF_UP).toPlainString(); }
}
