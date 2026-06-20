package com.nemeles.npcai;

import com.nemeles.core.api.NemelesApi;
import com.nemeles.core.api.economy.MoneyType;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * TRATOS DE CALLE: vende marihuana a CUALQUIER vecino de la ciudad (clic derecho con la bolsa).
 * El NPC ofrece un precio (segun su afinidad contigo), puedes REGATEAR una vez (clic normal otra vez)
 * y CIERRAS agachado+clic. Riesgos reales: si te odia puede DENUNCIARTE a la Costa al terminar,
 * y a veces el "vecino"... era un AGENTE ENCUBIERTO. Mas confianza = mejor precio y menos riesgo.
 */
public final class StreetDealManager {

    private static final class Deal {
        UUID npcId;
        String personaKey;
        double pricePerBag;
        boolean haggled;
        long expiresAt;
    }

    private final Plugin plugin;
    private final AffinityManager affinity;   // puede ser null
    private final NamespacedKey weedKey = NamespacedKey.fromString("nemelesjobs:weed_type");
    private final Map<UUID, Deal> deals = new ConcurrentHashMap<>();

    private final double basePrice;
    private final double undercoverChance;

    public StreetDealManager(Plugin plugin, AffinityManager affinity) {
        this.plugin = plugin;
        this.affinity = affinity;
        this.basePrice = plugin.getConfig().getDouble("street-deal.base-price", 230.0);
        this.undercoverChance = plugin.getConfig().getDouble("street-deal.undercover-chance", 0.07);
    }

    /** ¿Lleva bolsas de maria en la mano? */
    public boolean holdingBags(Player p) {
        ItemStack it = p.getInventory().getItemInMainHand();
        if (it == null || weedKey == null || !it.hasItemMeta()) return false;
        String type = it.getItemMeta().getPersistentDataContainer().get(weedKey, PersistentDataType.STRING);
        return "bag".equals(type);
    }

    /** Clic derecho en un NPC con la bolsa en mano. Maneja TODO el flujo del trato. */
    public void interact(Player p, NpcPersona persona, Entity npc) {
        UUID pid = p.getUniqueId();
        Deal d = deals.get(pid);
        long now = System.currentTimeMillis();
        if (d != null && (d.expiresAt < now || !d.npcId.equals(npc.getUniqueId()))) {
            deals.remove(pid);
            d = null;
        }
        int bags = p.getInventory().getItemInMainHand().getAmount();

        if (d == null) {
            // ── OFERTA: el NPC tantea el genero y pone precio segun lo que le caes
            int aff = 0;
            if (affinity != null) { try { aff = affinity.get(persona.key, pid); } catch (Throwable ignored) { } }
            double mult = 1.0 + Math.max(-0.25, Math.min(0.25, aff / 400.0))
                    + ThreadLocalRandom.current().nextDouble(-0.08, 0.08);
            // PRECIO DINÁMICO: el mercado server-wide de maría también afecta al trato de calle
            double market = 1.0;
            try { market = NemelesApi.heat().priceMultiplier("weed"); } catch (Throwable ignored) { }
            Deal nd = new Deal();
            nd.npcId = npc.getUniqueId();
            nd.personaKey = persona.key;
            nd.pricePerBag = Math.max(40, basePrice * mult * market);
            if (market < 0.85) {
                send(p, "&8(" + persona.name + " masculla: 'media ciudad vende lo mismo... el precio está por los suelos')");
            }
            nd.expiresAt = now + 25_000L;
            deals.put(pid, nd);
            String[] opens = {
                    "mira a ambos lados y susurra: 'Te doy &f$%s&7 por bolsa. Lo tomas o lo dejas.'",
                    "huele el genero con disimulo: '&f$%s&7 la bolsa. Ni un duro mas... en principio.'",
                    "se hace el desinteresado: 'Bah... &f$%s&7 por bolsa y no me hagas perder el tiempo.'"
            };
            send(p, "&7" + persona.name + " " + String.format(pick(opens), fmt(nd.pricePerBag)));
            send(p, "&8(clic OTRA VEZ = regatear · AGACHADO+clic = cerrar el trato por " + bags + " bolsa(s))");
            return;
        }

        if (p.isSneaking()) {
            // ── CIERRE del trato
            deals.remove(pid);
            closeDeal(p, persona, npc, d, bags);
        } else if (!d.haggled) {
            // ── REGATEO (solo una vez): puede subir... o torcerse
            d.haggled = true;
            double roll = ThreadLocalRandom.current().nextDouble();
            if (roll < 0.5) {
                d.pricePerBag *= 1.0 + ThreadLocalRandom.current().nextDouble(0.10, 0.22);
                send(p, "&7" + persona.name + " resopla: 'Esta bien, ladron... &f$" + fmt(d.pricePerBag) + "&7 por bolsa. ULTIMA oferta.'");
            } else if (roll < 0.8) {
                send(p, "&7" + persona.name + " no se mueve: 'He dicho &f$" + fmt(d.pricePerBag) + "&7. ¿Si o no?'");
            } else {
                d.pricePerBag *= 0.90;
                if (affinity != null) { try { affinity.bump(persona.key, pid, -2); } catch (Throwable ignored) { } }
                send(p, "&c" + persona.name + " se ofende: 'Por listo, ahora son &f$" + fmt(d.pricePerBag) + "&c. Y da gracias.'");
            }
        } else {
            send(p, "&8(" + persona.name + " ya dijo su última palabra: agáchate y haz clic para cerrar... o vete)");
        }
    }

    private void closeDeal(Player p, NpcPersona persona, Entity npc, Deal d, int bags) {
        UUID pid = p.getUniqueId();
        // ¿AGENTE ENCUBIERTO? La venta NO ocurre y la Costa se te echa encima.
        if (ThreadLocalRandom.current().nextDouble() < undercoverChance) {
            send(p, "&4☠ " + persona.name + " saca una PLACA: '¡Unidad Costa! ¡Quieto ahí!' &c¡Era un agente encubierto!");
            try { p.playSound(p.getLocation(), "entity.wither.spawn", 0.6f, 1.6f); } catch (Throwable ignored) { }
            try { NemelesApi.wanted().addCrime(pid, 30, "deal:undercover", p.getLocation(), false); } catch (Throwable ignored) { }
            alertPolice(p, "&9📻 [Central] Un encubierto pilló a un camello EN PLENA VENTA. Zona marcada: ¡a por él!");
            return;
        }
        // verificar bolsas en mano y cobrar
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!holdingBags(p) || hand.getAmount() < bags) { send(p, "&c¿Y el género? Sin bolsas no hay trato."); return; }
        double total = d.pricePerBag * bags;
        hand.setAmount(hand.getAmount() - bags);
        BigDecimal amt = BigDecimal.valueOf(total).setScale(2, RoundingMode.HALF_UP);
        NemelesApi.economy().deposit(pid, MoneyType.SUCIO, amt, "deal:street:" + d.personaKey);
        try { NemelesApi.heat().onDrugSale(pid, "weed", bags); } catch (Throwable ignored) { }
        if (affinity != null) { try { affinity.bump(persona.key, pid, 2); } catch (Throwable ignored) { } }
        send(p, "&a" + persona.name + " te pasa el dinero con un apretón de manos: &f+$" + amt.toPlainString()
                + " sucios &7(" + bags + " bolsa(s) a $" + fmt(d.pricePerBag) + ")");
        try { p.playSound(p.getLocation(), "entity.villager.trade", 0.8f, 0.9f); } catch (Throwable ignored) { }

        // ¿te DENUNCIA despues? (depende de como le caes)
        int aff = 0;
        if (affinity != null) { try { aff = affinity.get(persona.key, pid); } catch (Throwable ignored) { } }
        double reportChance = aff <= -15 ? 0.30 : aff >= 15 ? 0.04 : 0.12;
        if (ThreadLocalRandom.current().nextDouble() < reportChance) {
            final UUID fpid = pid;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player p2 = Bukkit.getPlayer(fpid);
                if (p2 == null) return;
                send(p2, "&c" + persona.name + " se aleja deprisa... y lo ves hablando por teléfono mirándote. &4Te ha DENUNCIADO.");
                try { NemelesApi.wanted().addCrime(fpid, 10, "deal:reported", p2.getLocation(), false); } catch (Throwable ignored) { }
                alertPolice(p2, "&9📻 [Central] Un vecino denuncia una venta de droga que acaba de ocurrir. Ojo a la zona.");
            }, 60L + ThreadLocalRandom.current().nextInt(80));
        }
    }

    /** Aviso a los policias DE SERVICIO (sin nombre: que investiguen). */
    private void alertPolice(Player near, String msg) {
        String colored = ChatColor.translateAlternateColorCodes('&', msg);
        for (Player onl : Bukkit.getOnlinePlayers()) {
            try {
                var career = NemelesApi.careers().careerOf(onl.getUniqueId());
                if (career.isPresent() && career.get().equals("police")
                        && NemelesApi.careers().isOnDuty(onl.getUniqueId())) {
                    onl.sendMessage(colored);
                    onl.playSound(onl.getLocation(), "block.note_block.pling", 0.9f, 1.3f);
                }
            } catch (Throwable ignored) { }
        }
    }

    private void send(Player p, String s) {
        p.sendMessage(ChatColor.translateAlternateColorCodes('&', s));
    }

    private static String pick(String[] arr) { return arr[ThreadLocalRandom.current().nextInt(arr.length)]; }
    private static String fmt(double v) { return BigDecimal.valueOf(v).setScale(0, RoundingMode.HALF_UP).toPlainString(); }
}
