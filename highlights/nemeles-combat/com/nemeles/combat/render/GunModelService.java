package com.nemeles.combat.render;

import com.nemeles.combat.CombatConfig;
import com.nemeles.combat.GunDef;
import com.nemeles.combat.GunRegistry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renderiza en 3a persona el modelo 3D del arma equipada usando BetterModel (paridad Java<->Bedrock
 * via GeyserModelEngine). NO toca BD ni PDC: es puramente visual/runtime. El PDC del item ya basta
 * para saber QUE modelo montar (gunId [+ skinId]).
 *
 * <p>Enfoque (verificado contra BetterModel 2.2.0): se "disfraza" al jugador con un modelo cuyos
 * bones de cuerpo llevan los prefijos limb (ph/pra/pla/...) para que BetterModel los pinte con la
 * SKIN REAL del jugador (via {@link ModelProfile#of}), y el arma cuelga del brazo. Al disparar/recargar
 * se reproduce la animacion .tpp sobre el {@code EntityTracker} (entidad real) -> propaga sola a Bedrock.
 *
 * <p><b>Soft-dependency:</b> toda la integracion con BetterModel se hace por REFLEXION para que el
 * modulo de combate compile y funcione aunque BetterModel NO este presente. Si la API no carga, el
 * servicio queda inerte (todos los metodos son no-op) y el combate sigue funcionando sin el 3D.
 *
 * <p>Estado por jugador: el id de modelo BetterModel actualmente montado (para reconciliar). El
 * tracker NO se cachea como referencia fuerte: se recupera del registry del jugador cuando hace falta
 * animar/quitar (asi no se queda colgando si BetterModel lo cierra por su cuenta, p.ej. al cambiar de mundo).
 */
public final class GunModelService {

    private final Plugin plugin;
    private final GunRegistry guns;

    /** gunId (lowercase) -> id de modelo BetterModel (.bbmodel importado). De config: combat-models.weapon. */
    private final Map<String, String> modelByGun = new HashMap<>();
    /** skinId (lowercase) -> id de modelo BetterModel. Tiene prioridad sobre el del arma base. */
    private final Map<String, String> modelBySkin = new HashMap<>();
    /** gunId -> prefijo de las animaciones .tpp (p.ej. "animation.deagle." para pistol). De config. */
    private final Map<String, String> animPrefixByGun = new HashMap<>();
    private String defaultAnimPrefix = "";   // nombres de animacion CRUDOS (draw/idle/shoot/...) = convencion validada con demon_knight

    /** gunId -> frames de custom_model_data para el RETROCESO en 1a persona (Java). Flipbook del item en mano (no usa BetterModel). */
    private final Map<String, int[]> recoilFp = new HashMap<>();

    /** UUID -> id de modelo BetterModel montado AHORA mismo sobre ese jugador (null = ninguno). */
    private final Map<UUID, String> mounted = new ConcurrentHashMap<>();

    /** true solo si la API de BetterModel se resolvio por reflexion al arrancar. */
    private final boolean available;
    private final BmApi bm;   // wrapper de reflexion; null si no disponible

    // Floodgate (soft-dep por reflexion): detectar jugadores Bedrock para OCULTARLES el disfraz (usan su attachable AG2).
    private Object fgApi;
    private java.lang.reflect.Method fgIsFloodgate;

    private BukkitTask reconcileTask;

    public GunModelService(Plugin plugin, GunRegistry guns, CombatConfig cfg) {
        this.plugin = plugin;
        this.guns = guns;
        loadMappings(cfg);
        recoilFp.put("pistol", new int[]{4320, 4321});   // deagle: retroceso 1a persona (variantes pistol_recoil1/2)
        BmApi api = null;
        boolean ok = false;
        try {
            if (Bukkit.getPluginManager().getPlugin("BetterModel") != null) {
                api = new BmApi();   // resuelve clases/metodos por reflexion (lanza si la firma no cuadra)
                ok = true;
            } else {
                plugin.getLogger().info("[GunModel] BetterModel no presente: armas 3D desactivadas (combate normal OK).");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[GunModel] No se pudo enlazar la API de BetterModel ("
                    + t.getClass().getSimpleName() + ": " + t.getMessage() + "). Armas 3D desactivadas.");
        }
        this.bm = api;
        this.available = ok;
        initFloodgate();
    }

    public boolean isAvailable() { return available; }

    /** Resuelve FloodgateApi por reflexion (soft-dep). Si falta, isBedrock() devuelve siempre false (todos como Java). */
    private void initFloodgate() {
        try {
            Class<?> cApi = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            fgApi = cApi.getMethod("getInstance").invoke(null);
            fgIsFloodgate = cApi.getMethod("isFloodgatePlayer", java.util.UUID.class);
        } catch (Throwable t) {
            fgApi = null; fgIsFloodgate = null;
        }
    }

    /** true si el jugador es Bedrock (cliente Geyser/Floodgate). A esos se les OCULTA el disfraz (ven su attachable AG2). */
    private boolean isBedrock(java.util.UUID uuid) {
        if (fgApi == null || fgIsFloodgate == null) return false;
        try { return Boolean.TRUE.equals(fgIsFloodgate.invoke(fgApi, uuid)); }
        catch (Throwable t) { return false; }
    }

    /**
     * RETROCESO EN 1a PERSONA (Java): intercambia el custom_model_data del arma en mano por una secuencia de
     * frames (variantes del modelo con la transform de 1a persona movida) y restaura al CMD base. NO depende de
     * BetterModel (es manipulacion del item vanilla). Bedrock anima por su attachable AG2 (no se toca aqui).
     */
    public void playRecoilFp(Player player, GunDef gun) {
        if (player == null || gun == null) return;
        int[] frames = recoilFp.get(gun.id);
        if (frames == null || frames.length == 0) return;
        final int baseCmd = gun.modelData;   // CMD base del arma (sin skin)
        for (int i = 0; i < frames.length; i++) {
            final int cmd = frames[i];
            Bukkit.getScheduler().runTaskLater(plugin, () -> setHeldCmd(player, gun, cmd), (long) i);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> setHeldCmd(player, gun, baseCmd), (long) frames.length);
    }

    /** Aplica un custom_model_data al arma en mano SOLO si el jugador sigue sosteniendo esa misma arma. */
    private void setHeldCmd(Player player, GunDef gun, int cmd) {
        try {
            org.bukkit.inventory.ItemStack it = player.getInventory().getItemInMainHand();
            if (it == null || it.getType() != gun.baseItem) return;
            GunDef cur = guns.fromItem(it);
            if (cur == null || cur.id == null || !cur.id.equals(gun.id)) return;   // ya no sostiene esta arma
            org.bukkit.inventory.meta.ItemMeta m = it.getItemMeta();
            if (m == null) return;
            m.setCustomModelData(cmd);
            it.setItemMeta(m);
            player.getInventory().setItemInMainHand(it);
        } catch (Throwable ignored) { }
    }

    /**
     * Mapeo (gunId/skinId) -> modelo BetterModel y prefijo de animacion. Lee opcionalmente una seccion
     * "combat-models" de la config; si no existe, usa defaults razonables (pistol -> gun_deagle).
     * Formato esperado en config.yml (todo opcional):
     * <pre>
     * combat-models:
     *   default-anim-prefix: "animation.deagle."
     *   weapons:
     *     pistol:  { model: gun_deagle,  anim-prefix: "animation.deagle." }
     *     rifle:   { model: gun_ak,      anim-prefix: "animation.ak." }
     *   skins:
     *     deagle_blaze: gun_deagle_blaze
     * </pre>
     */
    private void loadMappings(CombatConfig cfg) {
        // Defaults: solo el deagle (pistol) tiene assets validados en Fase 1; el resto se anade por config.
        modelByGun.put("pistol", "deagle");
        animPrefixByGun.put("pistol", "");

        try {
            org.bukkit.configuration.file.FileConfiguration c = ((org.bukkit.plugin.java.JavaPlugin) plugin).getConfig();
            org.bukkit.configuration.ConfigurationSection root = c.getConfigurationSection("combat-models");
            if (root == null) return;
            defaultAnimPrefix = root.getString("default-anim-prefix", defaultAnimPrefix);
            org.bukkit.configuration.ConfigurationSection ws = root.getConfigurationSection("weapons");
            if (ws != null) for (String gun : ws.getKeys(false)) {
                String key = gun.toLowerCase(java.util.Locale.ROOT);
                org.bukkit.configuration.ConfigurationSection w = ws.getConfigurationSection(gun);
                if (w != null) {
                    String model = w.getString("model", "");
                    if (!model.isBlank()) modelByGun.put(key, model);
                    String ap = w.getString("anim-prefix", "");
                    if (!ap.isBlank()) animPrefixByGun.put(key, ap);
                } else {
                    String model = ws.getString(gun, "");   // forma corta: pistol: gun_deagle
                    if (!model.isBlank()) modelByGun.put(key, model);
                }
            }
            org.bukkit.configuration.ConfigurationSection sk = root.getConfigurationSection("skins");
            if (sk != null) for (String skin : sk.getKeys(false)) {
                String model = sk.getString(skin, "");
                if (!model.isBlank()) modelBySkin.put(skin.toLowerCase(java.util.Locale.ROOT), model);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[GunModel] Error leyendo 'combat-models' de la config: " + t.getMessage());
        }
    }

    /** Arranca el tick de reconciliacion (cubre drag/drop/downed/death/world sin tocar esos listeners). */
    public void start() {
        if (!available) return;
        reconcileTask = Bukkit.getScheduler().runTaskTimer(plugin, this::reconcileAll, 20L, 5L);  // cada ~0.25s
    }

    // ===================== API publica (la que llaman GunListener/AmmoManager/listeners) =====================

    /** Llamar cuando el jugador pasa a empunar un arma. Idempotente. */
    public void onEquip(Player player, String gunId, String skinId) {
        if (!available || player == null || gunId == null) return;
        String modelId = resolveModel(gunId, skinId);
        if (modelId == null) { onHolster(player); return; }
        UUID id = player.getUniqueId();
        String current = mounted.get(id);
        if (modelId.equals(current)) return;   // ya montado: nada que hacer
        if (current != null) safe(() -> bm.remove(player, current));   // cambio de arma: cerrar el anterior
        // hideFilter = isBedrock: el disfraz NO se manda a viewers Bedrock (ellos ven su attachable AG2 -> cero doble).
        boolean ok = safe(() -> bm.equip(player, modelId, this::isBedrock));
        if (ok) {
            mounted.put(id, modelId);
            // DRAW -> IDLE en loop (si las animaciones no existen, animate() devuelve false y no pasa nada)
            String pref = animPrefix(gunId);
            safeRun(() -> bm.animateOnceThenLoop(player, modelId, pref + "draw", pref + "idle"));
        }
    }

    /** Llamar cuando el jugador deja de empunar un arma (cambio a item no-arma). */
    public void onHolster(Player player) {
        if (!available || player == null) return;
        String current = mounted.remove(player.getUniqueId());
        if (current != null) safe(() -> bm.remove(player, current));
    }

    /** Animacion de disparo (one-shot). Llamada desde GunListener tras confirmar el tiro. */
    public void onShoot(Player player, GunDef gun) {
        if (!available || player == null || gun == null) return;
        String modelId = mounted.get(player.getUniqueId());
        if (modelId == null) return;
        String pref = animPrefix(gun.id);
        // si hay balas dispara shoot.tpp; si quedo a 0, shoot_empty.tpp (corredera atras)
        boolean empty = false;
        try {
            ItemStack inHand = player.getInventory().getItemInMainHand();
            empty = guns.mag(inHand) <= 0;
        } catch (Throwable ignored) { }
        String anim = pref + (empty ? "shoot_empty" : "shoot");
        safeRun(() -> bm.animateOnceThenLoop(player, modelId, anim, pref + "idle"));
    }

    /** Animacion de recarga (one-shot, dura lo que dure la anim). Llamada desde AmmoManager al iniciar reload. */
    public void onReload(Player player, GunDef gun) {
        if (!available || player == null || gun == null) return;
        String modelId = mounted.get(player.getUniqueId());
        if (modelId == null) return;
        String pref = animPrefix(gun.id);
        safeRun(() -> bm.animateOnceThenLoop(player, modelId, pref + "reload", pref + "idle"));
    }

    /** Fin de recarga (opcional): asegura volver a idle. Inofensivo si la reload ya termino sola. */
    public void onReloadEnd(Player player) {
        if (!available || player == null) return;
        String modelId = mounted.get(player.getUniqueId());
        if (modelId == null) return;
        String gunId = mountedGunId(player);
        String pref = gunId != null ? animPrefix(gunId) : defaultAnimPrefix;
        safeRun(() -> bm.loop(player, modelId, pref + "idle"));
    }

    /** Quitar el modelo de ESTE jugador (quit/death/world-change). Cierra todo su registry para no dejar fantasmas. */
    public void cleanup(Player player) {
        if (!available || player == null) return;
        mounted.remove(player.getUniqueId());
        safe(() -> bm.clearAll(player));
    }

    /** Apagado del plugin: quitar TODOS los modelos para no dejar entidades/trackers huerfanos. */
    public void cleanupAll() {
        if (reconcileTask != null) { reconcileTask.cancel(); reconcileTask = null; }
        if (!available) { mounted.clear(); return; }
        for (UUID id : new java.util.ArrayList<>(mounted.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) safe(() -> bm.clearAll(p));
        }
        mounted.clear();
    }

    // ===================== Reconciliacion (red de seguridad) =====================

    /**
     * Compara, para cada jugador online, "arma en mano AHORA" vs "modelo montado AHORA" y monta/desmonta.
     * Cubre los casos que los eventos no ven: arrastrar otro item al slot de la mano, drop del arma,
     * romper stack, ser derribado (item sigue en mano pero ya no empuna -> opcional), cambio de mundo, etc.
     */
    private void reconcileAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { reconcile(p); } catch (Throwable ignored) { }
        }
    }

    private void reconcile(Player p) {
        ItemStack inHand = p.getInventory().getItemInMainHand();
        String gunId = guns.gunIdOf(inHand);
        String wantModel = gunId == null ? null : resolveModel(gunId, guns.skinIdOf(inHand));
        String haveModel = mounted.get(p.getUniqueId());
        if (java.util.Objects.equals(wantModel, haveModel)) return;
        if (wantModel == null) onHolster(p);
        else onEquip(p, gunId, guns.skinIdOf(inHand));
    }

    // ===================== Helpers de mapeo =====================

    /** (gunId[, skinId]) -> id de modelo BetterModel a montar, o null si no hay modelo para esa arma. */
    private String resolveModel(String gunId, String skinId) {
        if (skinId != null) {
            String m = modelBySkin.get(skinId.toLowerCase(java.util.Locale.ROOT));
            if (m != null) return m;
        }
        return gunId == null ? null : modelByGun.get(gunId.toLowerCase(java.util.Locale.ROOT));
    }

    private String animPrefix(String gunId) {
        if (gunId == null) return defaultAnimPrefix;
        return animPrefixByGun.getOrDefault(gunId.toLowerCase(java.util.Locale.ROOT), defaultAnimPrefix);
    }

    /** Resuelve el gunId del arma en mano (para elegir el prefijo de anim en onReloadEnd). */
    private String mountedGunId(Player p) {
        try { return guns.gunIdOf(p.getInventory().getItemInMainHand()); } catch (Throwable t) { return null; }
    }

    // ===================== Envoltura defensiva =====================

    private boolean safe(java.util.concurrent.Callable<Boolean> action) {
        try { Boolean r = action.call(); return r != null && r; }
        catch (Throwable t) { return false; }
    }

    private void safeRun(Runnable action) {
        try { action.run(); } catch (Throwable ignored) { }
    }

    // ====================================================================================================
    //  BmApi: toda la API de BetterModel resuelta por REFLEXION (soft-dependency real, compila sin el jar).
    //  Las firmas estan verificadas contra BetterModel-2.2.0-paper:
    //    BetterModel.modelOrNull(String) -> ModelRenderer
    //    BetterModel.registryOrNull(UUID) -> EntityTrackerRegistry
    //    BukkitAdapter.adapt(org.bukkit.entity.Entity)  -> PlatformEntity
    //    BukkitAdapter.adapt(org.bukkit.entity.Player)  -> PlatformPlayer
    //    ModelProfile.of(PlatformPlayer) -> ModelProfile
    //    ModelRenderer.getOrCreate(PlatformEntity, ModelProfile, TrackerModifier, Consumer) -> EntityTracker
    //    EntityTrackerRegistry.tracker(String) -> EntityTracker ; .remove(String) ; .close()
    //    Tracker.animate(String) / animate(String, AnimationModifier) / animate(String, AnimationModifier, Runnable)
    //    AnimationModifier.DEFAULT / DEFAULT_WITH_PLAY_ONCE
    // ====================================================================================================
    private static final class BmApi {
        private final java.lang.reflect.Method mModelOrNull;     // BetterModel.modelOrNull(String)
        private final java.lang.reflect.Method mRegistryOrNull;  // BetterModel.registryOrNull(UUID)
        private final java.lang.reflect.Method mAdaptEntity;     // BukkitAdapter.adapt(Entity)
        private final java.lang.reflect.Method mAdaptPlayer;     // BukkitAdapter.adapt(Player)
        private final java.lang.reflect.Method mProfileOf;       // ModelProfile.of(PlatformPlayer)
        private final java.lang.reflect.Method mGetOrCreate;     // ModelRenderer.getOrCreate(PlatformEntity, ModelProfile, TrackerModifier, Consumer)
        private final java.lang.reflect.Method mRendererName;    // ModelRenderer.name()
        private final java.lang.reflect.Method mRegTracker;      // EntityTrackerRegistry.tracker(String)
        private final java.lang.reflect.Method mRegRemove;       // EntityTrackerRegistry.remove(String)
        private final java.lang.reflect.Method mRegClose;        // EntityTrackerRegistry.close()
        private final java.lang.reflect.Method mAnimate1;        // Tracker.animate(String)
        private final java.lang.reflect.Method mAnimate3;        // Tracker.animate(String, AnimationModifier, Runnable)
        private final java.lang.reflect.Method mGetPipeline;     // Tracker.getPipeline()
        private final java.lang.reflect.Method mHideFilter;      // RenderPipeline.hideFilter(Predicate)
        private final java.lang.reflect.Method mPpUuid;          // PlatformPlayer.uuid()
        private final Object modifierDefault;                    // AnimationModifier.DEFAULT
        private final Object modifierPlayOnce;                   // AnimationModifier.DEFAULT_WITH_PLAY_ONCE
        private final Object trackerModifierDefault;             // TrackerModifier.DEFAULT

        BmApi() throws Exception {
            Class<?> cBetterModel  = Class.forName("kr.toxicity.model.api.BetterModel");
            Class<?> cAdapter      = Class.forName("kr.toxicity.model.api.bukkit.platform.BukkitAdapter");
            Class<?> cProfile      = Class.forName("kr.toxicity.model.api.profile.ModelProfile");
            Class<?> cRenderer     = Class.forName("kr.toxicity.model.api.data.renderer.ModelRenderer");
            Class<?> cRegistry     = Class.forName("kr.toxicity.model.api.tracker.EntityTrackerRegistry");
            Class<?> cTracker      = Class.forName("kr.toxicity.model.api.tracker.Tracker");
            Class<?> cPlatEntity   = Class.forName("kr.toxicity.model.api.platform.PlatformEntity");
            Class<?> cPlatPlayer   = Class.forName("kr.toxicity.model.api.platform.PlatformPlayer");
            Class<?> cTrkModifier  = Class.forName("kr.toxicity.model.api.tracker.TrackerModifier");
            Class<?> cAnimModifier = Class.forName("kr.toxicity.model.api.animation.AnimationModifier");
            Class<?> cPipeline     = Class.forName("kr.toxicity.model.api.data.renderer.RenderPipeline");

            mModelOrNull    = cBetterModel.getMethod("modelOrNull", String.class);
            mRegistryOrNull = cBetterModel.getMethod("registryOrNull", UUID.class);
            mAdaptEntity    = cAdapter.getMethod("adapt", org.bukkit.entity.Entity.class);
            mAdaptPlayer    = cAdapter.getMethod("adapt", org.bukkit.entity.Player.class);
            mProfileOf      = cProfile.getMethod("of", cPlatPlayer);
            mGetOrCreate    = cRenderer.getMethod("getOrCreate", cPlatEntity, cProfile, cTrkModifier, java.util.function.Consumer.class);
            mRendererName   = cRenderer.getMethod("name");
            mRegTracker     = cRegistry.getMethod("tracker", String.class);
            mRegRemove      = cRegistry.getMethod("remove", String.class);
            mRegClose       = cRegistry.getMethod("close");
            mAnimate1       = cTracker.getMethod("animate", String.class);
            mAnimate3       = cTracker.getMethod("animate", String.class, cAnimModifier, Runnable.class);
            mGetPipeline    = cTracker.getMethod("getPipeline");
            mHideFilter     = cPipeline.getMethod("hideFilter", java.util.function.Predicate.class);
            mPpUuid         = cPlatPlayer.getMethod("uuid");

            modifierDefault       = cAnimModifier.getField("DEFAULT").get(null);
            modifierPlayOnce      = cAnimModifier.getField("DEFAULT_WITH_PLAY_ONCE").get(null);
            trackerModifierDefault = cTrkModifier.getField("DEFAULT").get(null);
        }

        /** Equipa el modelo sobre el jugador conservando su skin (ModelProfile.of(player)).
         *  hideViewerUuid: los viewers cuyo UUID cumpla el predicado NO veran el disfraz (Bedrock -> usan su attachable AG2).
         *  Como GME consulta pipeline.isHide para decidir si genera la proxy Bedrock, esto evita el doble en Bedrock. */
        boolean equip(Player player, String modelId, java.util.function.Predicate<java.util.UUID> hideViewerUuid) throws Exception {
            Object renderer = mModelOrNull.invoke(null, modelId);
            if (renderer == null) return false;                 // .bbmodel no importado / id mal escrito
            Object platEntity = mAdaptEntity.invoke(null, (org.bukkit.entity.Entity) player);
            Object platPlayer = mAdaptPlayer.invoke(null, player);
            Object profile = mProfileOf.invoke(null, platPlayer);   // captura la skin real (Java o Bedrock via Floodgate)
            java.util.function.Consumer<Object> onCreate = (Object tracker) -> {
                if (hideViewerUuid == null) return;
                try {
                    Object pipeline = mGetPipeline.invoke(tracker);
                    // Predicate<PlatformPlayer>: true = OCULTAR el modelo a ese viewer (lo aplica GME tambien via isHide).
                    java.util.function.Predicate<Object> filter = (Object pp) -> {
                        try { return hideViewerUuid.test((java.util.UUID) mPpUuid.invoke(pp)); }
                        catch (Throwable t) { return false; }
                    };
                    mHideFilter.invoke(pipeline, filter);
                } catch (Throwable ignored) { }
            };
            // getOrCreate es idempotente: usa renderer.name() como clave del tracker en el registry del jugador.
            mGetOrCreate.invoke(renderer, platEntity, profile, trackerModifierDefault, onCreate);
            return true;
        }

        /** Recupera el tracker del modelo (la clave del tracker = renderer.name()). */
        private Object tracker(Player player, String modelId) throws Exception {
            Object reg = mRegistryOrNull.invoke(null, player.getUniqueId());
            if (reg == null) return null;
            String key = trackerKey(modelId);
            return mRegTracker.invoke(reg, key);
        }

        /** La clave del tracker es el name() del ModelRenderer; cae al modelId si el modelo no existe. */
        private String trackerKey(String modelId) {
            try {
                Object renderer = mModelOrNull.invoke(null, modelId);
                if (renderer != null) {
                    Object n = mRendererName.invoke(renderer);
                    if (n != null) return n.toString();
                }
            } catch (Throwable ignored) { }
            return modelId;
        }

        /** Reproduce 'anim' una vez (PLAY_ONCE) y al terminar vuelve a 'loopAnim' en bucle. */
        void animateOnceThenLoop(Player player, String modelId, String anim, String loopAnim) {
            try {
                Object t = tracker(player, modelId);
                if (t == null) return;
                Runnable back = () -> { try { mAnimate1.invoke(t, loopAnim); } catch (Throwable ignored) { } };
                mAnimate3.invoke(t, anim, modifierPlayOnce, back);
            } catch (Throwable ignored) { }
        }

        /** Pone 'anim' en bucle (idle). */
        void loop(Player player, String modelId, String anim) {
            try {
                Object t = tracker(player, modelId);
                if (t != null) mAnimate1.invoke(t, anim);   // DEFAULT = loop si la animacion es loop por defecto
            } catch (Throwable ignored) { }
        }

        /** Cierra SOLO el tracker de este modelo (deja intactos otros trackers del jugador). */
        boolean remove(Player player, String modelId) throws Exception {
            Object reg = mRegistryOrNull.invoke(null, player.getUniqueId());
            if (reg == null) return false;
            Object r = mRegRemove.invoke(reg, trackerKey(modelId));
            return r instanceof Boolean b && b;
        }

        /** Cierra TODO el registry del jugador (death/quit): equivalente a /bm undisguise. */
        boolean clearAll(Player player) throws Exception {
            Object reg = mRegistryOrNull.invoke(null, player.getUniqueId());
            if (reg == null) return false;
            mRegClose.invoke(reg);
            return true;
        }

        // referencia usada para silenciar el warning de "modifierDefault sin usar" (lo dejamos disponible
        // por si en el futuro se quiere un animate con DEFAULT explicito).
        Object unusedDefault() { return modifierDefault; }
    }
}
