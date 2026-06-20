package com.nemeles.combat.body;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * NUTRICION estilo Project Zomboid: (1) comida EN MAL ESTADO (cruda/podrida) puede intoxicarte
 * (dano al estomago = abdomen del sistema de partes, hambre, nausea, incluso vomitar);
 * (2) ASCO POR REPETICION: comer siempre lo mismo te da nauseas y alimenta menos;
 * (3) BONUS de dieta variada. Todo conectado al cuerpo por partes.
 */
public final class FoodListener implements Listener {

    /** prob. de intoxicacion por alimento de riesgo */
    private static final Map<Material, Double> RIESGO = Map.ofEntries(
            Map.entry(Material.ROTTEN_FLESH, 0.80),
            Map.entry(Material.PUFFERFISH, 0.90),
            Map.entry(Material.SPIDER_EYE, 0.70),
            Map.entry(Material.POISONOUS_POTATO, 0.75),
            Map.entry(Material.CHICKEN, 0.30),          // pollo CRUDO
            Map.entry(Material.BEEF, 0.15),
            Map.entry(Material.PORKCHOP, 0.20),
            Map.entry(Material.MUTTON, 0.15),
            Map.entry(Material.RABBIT, 0.15),
            Map.entry(Material.COD, 0.20),
            Map.entry(Material.SALMON, 0.20)
    );

    private final BodyManager mgr;
    private final Map<UUID, Deque<Material>> lastMeals = new ConcurrentHashMap<>();

    public FoodListener(BodyManager mgr) { this.mgr = mgr; }

    @EventHandler(ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        Material food = e.getItem().getType();
        if (!food.isEdible()) return;
        var rng = ThreadLocalRandom.current();

        // ── 1. ¿en mal estado? ──
        Double risk = RIESGO.get(food);
        if (risk != null && rng.nextDouble() < risk) {
            BodyManager.PartState stomach = mgr.body(p.getUniqueId()).get(BodyPart.TORSO_INF);
            stomach.hp = Math.max(0, stomach.hp - 15);
            stomach.infectionRisk = Math.min(1, stomach.infectionRisk + 0.05);
            try {
                p.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 30 * 20, 1, false, false));
                p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 15 * 20, 0, false, false));
            } catch (Throwable ignored) { }
            if (rng.nextDouble() < 0.35) {   // vomitas: pierdes lo comido y mas
                p.setFoodLevel(Math.max(0, p.getFoodLevel() - 5));
                p.sendMessage(color("&2Eso estaba PODRIDO. El estómago te lo devuelve entero... y con intereses."));
                try { p.playSound(p.getLocation(), "entity.player.burp", 1f, 0.5f); } catch (Throwable ignored) { }
            } else {
                p.sendMessage(color("&2Te cae como una piedra. Eso no estaba en buen estado... tu abdomen protesta."));
            }
            return;   // intoxicado: no evaluamos variedad
        }

        // ── 2. repeticion / variedad (ultimas 8 comidas) ──
        Deque<Material> meals = lastMeals.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
        long repeats = meals.stream().filter(m -> m == food).count();
        meals.addLast(food);
        while (meals.size() > 8) meals.removeFirst();

        if (repeats >= 4) {
            try {
                p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 10 * 20, 0, false, false));
                p.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 8 * 20, 0, false, false));
            } catch (Throwable ignored) { }
            p.sendMessage(color("&2No puedes ni tragarlo: llevas días comiendo LO MISMO. Tu cuerpo lo rechaza."));
        } else if (repeats >= 2) {
            p.setSaturation(Math.max(0, p.getSaturation() - 2.5f));
            if (rng.nextDouble() < 0.6) {
                p.sendMessage(color("&7Otra vez " + foodName(food) + "... lo comes sin ganas. (alimenta menos: varía la dieta)"));
            }
        } else if (meals.size() >= 5 && new HashSet<>(meals).size() == meals.size()) {
            p.setSaturation(Math.min(20f, p.getSaturation() + 2f));
            if (rng.nextDouble() < 0.4) {
                p.sendMessage(color("&aDieta variada: el cuerpo lo agradece. Te sienta de maravilla."));
            }
        }
    }

    private static String foodName(Material m) {
        return m.name().toLowerCase().replace('_', ' ');
    }

    private static String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
}
