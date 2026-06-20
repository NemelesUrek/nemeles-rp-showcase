package com.nemeles.combat.body;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * Textos de VISIÓN GRADUAL del cuerpo (qué ve un lego vs un médico). Cargados de
 * plugins/NemelesCombat/vision_medica.yml (escritos por el equipo de IAs). 3 niveles por condición.
 * Si el yml no está, usa los integrados. Estático: lo lee la BodyGUI al pintar cada parte.
 */
public final class VisionTexts {

    private static final Map<String, String[]> TEXTS = new HashMap<>();

    static { defaults(); }

    private VisionTexts() { }

    public static void load(Plugin plugin) {
        try {
            File f = new File(plugin.getDataFolder(), "vision_medica.yml");
            if (!f.exists()) return;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            Map<String, String[]> fresh = new HashMap<>();
            for (String key : y.getKeys(false)) {
                List<String> l = y.getStringList(key);
                if (l.size() >= 3) fresh.put(key.toUpperCase(Locale.ROOT), new String[]{l.get(0), l.get(1), l.get(2)});
            }
            if (!fresh.isEmpty()) { TEXTS.clear(); TEXTS.putAll(fresh); }
        } catch (Throwable ignored) { }
    }

    /** texto de la condición para el nivel de vision 0/1/2 (clampa). Si no existe la clave de
     *  subtipo (p.ej. FRACTURA_2), cae a la clave base (FRACTURA) para no dejar el lore vacío. */
    public static String get(String cond, int vlvl) {
        if (cond == null) return null;
        String key = cond.toUpperCase(Locale.ROOT);
        String[] arr = TEXTS.get(key);
        if (arr == null) {
            int u = key.lastIndexOf('_');
            if (u > 0) arr = TEXTS.get(key.substring(0, u));   // FRACTURA_2 -> FRACTURA
        }
        if (arr == null) return null;
        return arr[Math.max(0, Math.min(2, vlvl))];
    }

    private static void put(String k, String l0, String l1, String l2) { TEXTS.put(k, new String[]{l0, l1, l2}); }

    private static void defaults() {
        put("SANGRADO",
                "Le mana sangre roja y va manchándolo todo; está pálido y no para de gotear.",
                "Hemorragia activa. Aprieta la zona y ciérrala con una venda antes de nada.",
                "Sangrado venoso constante. Presión directa y venda estéril; trata esto primero.");
        put("SANGRADO_GRAVE",
                "Un chorro de sangre a borbotones; se le va el color y se tambalea, urge ya.",
                "Sangrado a chorro. En brazo o pierna pon torniquete; en torso, venda estéril fuerte.",
                "Hemorragia arterial: muerte en minutos. Torniquete en extremidad o presión+venda estéril YA.");
        put("CORTE",
                "Tiene un tajo abierto en la piel que sangra un poco y le escuece.",
                "Corte abierto. Límpialo, desinfecta y cúbrelo con una venda para que cierre.",
                "Laceración superficial. Desinfectante y venda; vigila que no se ensucie e infecte.");
        put("CORTE_PROFUNDO",
                "El tajo es hondo, se le abren los bordes y se ve la carne por dentro.",
                "Herida profunda y abierta. Hay que coserla con kit de sutura, no basta vendar.",
                "Laceración profunda con bordes separados. Desinfecta, sutura los planos y luego venda.");
        put("CONTUSION",
                "Tiene un moratón hinchado; se queja al tocarlo pero no hay herida abierta.",
                "Golpe sin herida abierta. Reposo, frío si hay, y analgésico para el dolor.",
                "Contusión de tejido blando, sin herida. Analgésico y reposo; no precisa sutura.");
        put("CONTUSION_1",
                "Tiene un raspón rojo en la piel; escuece pero es superficial.",
                "Rozadura/raspón. Límpialo y cúbrelo con una venda; nada serio.",
                "Erosión cutánea superficial. Limpieza y apósito; sin complicaciones.");
        put("CONTUSION_2",
                "Tiene un moratón morado e hinchado; se queja al tocarlo.",
                "Moratón (contusión). Reposo, frío y un analgésico para el dolor.",
                "Hematoma de tejido blando. Frío, reposo y analgésico; vigila que no crezca.");
        put("CONTUSION_3",
                "Un golpe muy fuerte: la zona está muy hinchada y morada, le duele un montón.",
                "Contusión fuerte. Reposo, venda compresiva y analgésico; que no la use.",
                "Contusión severa con edema. Compresión, analgésico y reposo; descarta fractura.");
        put("FRACTURA_1",
                "Le duele al apoyar y la zona está hinchada; se mueve raro pero no cuelga.",
                "Hueso fisurado. Inmoviliza con una férula y dale algo para el dolor.",
                "Fisura ósea sin desplazamiento. Férula rígida y analgésico; nada de carga hasta soldar.");
        put("FRACTURA_2",
                "La extremidad cuelga deformada y cruje; grita en cuanto intenta moverla.",
                "Hueso roto bajo la piel. Entablilla con férula y analgésico, y que no la use.",
                "Fractura cerrada desplazada con edema. Alinea, férula firme y analgésico; cojera asegurada.");
        put("FRACTURA_3",
                "El hueso ha roto la piel y asoma astillado, manando sangre; está fatal.",
                "Hueso roto que sale por la piel. Esto no te lo arreglas tú: necesita un médico de verdad.",
                "Fractura abierta: foco contaminado y hemorragia. Cohíbe sangrado, sutura y férula; solo avanzado.");
        put("ESGUINCE_1",
                "Cojea un poco y se sujeta la articulación, como si se la hubiera torcido.",
                "Torcedura leve. Venda de compresión y reposo; en unos minutos va mejor.",
                "Esguince grado 1, ligamento distendido. Vendaje compresivo y reposo; molestia residual.");
        put("ESGUINCE_2",
                "La articulación está muy hinchada y le falla; apenas puede apoyar sin doblarse.",
                "Torcedura fuerte e inestable. Venda elástica apretada, reposo y analgésico.",
                "Esguince grado 2 con inestabilidad. Vendaje elástico firme y analgésico; evitar carga.");
        put("QUEMADURA_1",
                "La piel está roja y caliente; se queja de un ardor que no se le pasa.",
                "Quemadura superficial. Aplica pomada para calmar el ardor y déjala respirar.",
                "Quemadura de primer grado, eritema sin ampolla. Pomada; no precisa cubrir.");
        put("QUEMADURA_2",
                "Tiene ampollas reventadas y la piel en carne viva; el dolor es horrible.",
                "Quemadura con ampollas. Pon pomada y cubre con venda estéril seca, sin reventarlas.",
                "Quemadura de segundo grado con flictenas. Pomada y venda estéril seca; alto riesgo de infección.");
        put("BALA",
                "Tiene un agujero de bala que sangra y se le nota la bola justo bajo la piel.",
                "Bala alojada cerca. Sácala con pinzas, desinfecta y venda el orificio.",
                "Proyectil superficial en tejido blando. Extrae con pinzas, desinfecta y venda; vigila sangrado.");
        put("BALA_PROFUNDA",
                "El balazo entró hondo, sangra mucho y ni se ve la bala; lo está pasando muy mal.",
                "Bala metida muy adentro. Hace falta bisturí y sutura, y un médico: no te la saques tú.",
                "Proyectil profundo. Incisión con bisturí, extracción y sutura; sangra al abrir, solo avanzado.");
        put("INFECCION",
                "La herida huele a podrido, está caliente y supura; el tío tiene muy mala cara.",
                "Herida infectada de verdad. Hacen falta antibióticos, vendar no la arregla.",
                "Infección declarada, supurativa. Antibiótico ya; cada foco resta vitalidad hasta erradicarlo.");
        put("INFECCION_RIESGO",
                "La herida está sucia y enrojecida por los bordes; tiene pinta de ir a peor.",
                "Herida sucia a punto de infectarse. Desinféctala ahora antes de que vaya a más.",
                "Foco contaminado, infección inminente. Desinfectante a tiempo evita antibióticos después.");
    }
}
