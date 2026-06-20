package com.nemeles.npcai;

/** Definición de un NPC conversacional: su nombre/clave, color, saludo y descripción de personaje. */
public final class NpcPersona {
    public final String key;       // nombre de la entidad con el que se activa (minúsculas)
    public final String name;      // nombre visible
    public final String color;     // código de color (&x)
    public final String greeting;  // saludo instantáneo (no usa IA)
    public final String persona;   // descripción de personaje (system prompt)
    public final String model;     // modelo Ollama propio (null = el global)
    public final double voicePitch; // tono de la "voz" de blips de este NPC
    public final String voiceSound; // sonido (timbre) propio de este NPC (null/"" = el global)
    public final String skinUrl;    // URL de textura: al CREAR el NPC se le aplica esta skin (null/"" = ninguna)
    public final boolean missionGiver; // si true, puede DARTE un trabajo del modulo de contratos al aceptar
    public final String weapon;        // material que DESENFUNDA al volverse hostil ("" = puños)
    public final String voiceGender;   // "f"/"m": la pagina de voz elige una voz TTS de ese genero (estable por NPC)
    // Ataque a DISTANCIA (pistola hitscan). Si ranged=true y el objetivo esta fuera del melee, dispara.
    public final boolean ranged;
    public final double rangedAccuracy;
    public final double rangedDamage;
    public final double rangedRange;
    public final long rangedCooldownMs;

    public NpcPersona(String key, String name, String color, String greeting, String persona, String model,
                      double voicePitch, String voiceSound, String skinUrl, boolean missionGiver, String weapon,
                      String voiceGender,
                      boolean ranged, double rangedAccuracy, double rangedDamage, double rangedRange,
                      long rangedCooldownMs) {
        this.key = key;
        this.name = name;
        this.color = color;
        this.greeting = greeting;
        this.persona = persona;
        this.model = model;
        this.voicePitch = voicePitch;
        this.voiceSound = voiceSound;
        this.skinUrl = skinUrl;
        this.missionGiver = missionGiver;
        this.weapon = weapon;
        this.voiceGender = voiceGender;
        this.ranged = ranged;
        this.rangedAccuracy = rangedAccuracy;
        this.rangedDamage = rangedDamage;
        this.rangedRange = rangedRange;
        this.rangedCooldownMs = rangedCooldownMs;
    }
}
