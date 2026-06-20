package com.nemeles.npcai;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Easter egg "alma gemela": UNA sola companera fiel en TODO el servidor para un NPC (por defecto Luna).
 * Solo el PRIMER jugador que complete pruebas extremas la consigue; despues queda cerrado para siempre.
 */
public final class BondConfig {

    public final boolean enabled;
    public final String persona;          // clave del NPC con alma gemela (p.ej. "luna")
    public final int minAffinity;         // afinidad requerida (y mantenida)
    public final int trustTurns;          // turnos de conversacion acumulados con ella
    public final boolean nightOnlyFinal;  // el paso final solo cuenta de noche (bajo la luna)
    public final List<String> phrases;    // cadena de frases secretas EN ORDEN (ya normalizadas)
    public final List<String> hints;      // pista criptica para CADA frase (misma longitud que phrases)
    public final String alreadyTaken, bondBroadcast, bondPrivate, progressStep, needNight, hintPrefix;
    public final String giftMaterial, giftName;
    public final java.util.Set<String> allies;   // "los suyos": personas que tampoco atacan al alma gemela (pase libre)
    public final double jealousyRadius;          // celos: radio alrededor del dueño donde un rival siente el miedo
    public final int betrayalAffinity;           // si la afinidad del dueño con Luna cae a esto (desamor), ella rompe el pacto
    public final String jealousyMsg, betrayalOwnerMsg, betrayalBroadcast, cannotHitMsg;

    public BondConfig(FileConfiguration c) {
        enabled        = c.getBoolean("bond.enabled", true);
        persona        = c.getString("bond.persona", "luna").toLowerCase(Locale.ROOT).trim();
        java.util.Set<String> al = new java.util.HashSet<>();
        for (String s : c.getStringList("bond.allies")) if (s != null && !s.isBlank()) al.add(s.toLowerCase(Locale.ROOT).trim());
        if (al.isEmpty()) al.add("miss");   // por defecto, Miss (la jefa de Luna) tampoco toca a su alma gemela
        allies = al;
        minAffinity    = c.getInt("bond.min-affinity", 100);
        trustTurns     = Math.max(0, c.getInt("bond.trust-turns", 150));
        nightOnlyFinal = c.getBoolean("bond.night-only-final", true);

        List<String> ph = new ArrayList<>();
        for (String s : c.getStringList("bond.phrases")) {
            String n = AiConfig.normalize(s);
            if (!n.isEmpty()) ph.add(n);
        }
        if (ph.isEmpty()) {
            ph.add(AiConfig.normalize("la luna no miente"));
            ph.add(AiConfig.normalize("veo a la persona no al angel"));
            ph.add(AiConfig.normalize("contigo no necesito fingir"));
        }
        phrases = ph;

        List<String> hi = new ArrayList<>(c.getStringList("bond.hints"));
        if (hi.size() < ph.size()) {
            String[] def = {
                "La luna ve lo que el sol esconde. Hay una verdad que solo diria quien acepta que la luna no miente.",
                "Todos ven un angel. Casi nadie ve... a la persona que hay debajo. Dimelo a tu manera.",
                "El dia que ya no tenga que fingir una sonrisa contigo, lo sabre. Dimelo de noche, cuando solo nos vea la luna."
            };
            hi = new ArrayList<>();
            for (int i = 0; i < ph.size(); i++) hi.add(i < def.length ? def[i] : "...");
        }
        hints = hi;

        alreadyTaken  = c.getString("bond.already-taken",
                "&d{npc} te sonrie con dulzura: &7lo siento, mi corazon ya tiene dueno. Pero siempre seras mi amigo.");
        bondBroadcast = c.getString("bond.bond-broadcast",
                "&d&l* &fEn algun rincon de Bahia Negra, {npc} ha encontrado a su persona. &7({player} se ha ganado el alma de {npc}.)");
        bondPrivate   = c.getString("bond.bond-private",
                "&d{npc} deja caer la sonrisa de siempre y te mira de verdad por primera vez: &fpor fin. Contigo no tengo que fingir, y tu no tienes que temerme. Estoy contigo. Para lo que sea. Para quien sea.");
        progressStep  = c.getString("bond.progress-step",
                "&d{npc} se queda un instante en silencio... y algo en ella cambia.");
        needNight     = c.getString("bond.need-night",
                "&7({npc} niega suavemente con la cabeza: esto... esto solo puedo decirlo de noche.)");
        hintPrefix    = c.getString("bond.hint-prefix", "&d{npc} baja la voz, solo para ti: &7");
        giftMaterial  = c.getString("bond.gift-material", "POPPY");
        giftName      = c.getString("bond.gift-name", "&dLazo de Luna");
        jealousyRadius   = Math.max(0, c.getDouble("bond.jealousy-radius", 5.0));
        betrayalAffinity = c.getInt("bond.betrayal-affinity", -15);
        jealousyMsg      = c.getString("bond.jealousy-message",
                "&5Sientes una presion helada en la nuca... la mirada de {npc} esta clavada en ti. &7No te acerques tanto a lo que es suyo.");
        betrayalOwnerMsg = c.getString("bond.betrayal-owner",
                "&d&l{npc} &fte mira con el corazon roto y la sonrisa de siempre: &7\"Asi que solo me usabas... y yo que te queria de verdad. Adios, mi amor.\"");
        betrayalBroadcast = c.getString("bond.betrayal-broadcast",
                "&5&l* &fEl alma de {npc} se ha roto. Quien la traiciono ya no respira; ella vuelve a estar libre... para quien sepa quererla de verdad.");
        cannotHitMsg     = c.getString("bond.cannot-hit",
                "&d{npc} esquiva tu golpe con una sonrisa dulce: &7\"jooo, ¿qué haces, tontito? A mí no me vas a hacer daño... y yo a ti tampoco. Tranquilo, estoy de tu lado.\"");
    }
}
