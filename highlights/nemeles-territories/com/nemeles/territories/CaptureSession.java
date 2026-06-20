package com.nemeles.territories;

import org.bukkit.boss.BossBar;

/** Captura en curso (en memoria; no se persiste: un reinicio reinicia la captura). */
final class CaptureSession {

    final int territoryId;
    final int attackerFaction;
    final long startedAt;
    int progressSeconds;        // 0..goalSeconds
    int goalSeconds;            // segundos efectivos de captura, FIJADOS al iniciar (lealtad del defensor de ese momento)
    BossBar bar;

    CaptureSession(int territoryId, int attackerFaction, long startedAt) {
        this.territoryId = territoryId;
        this.attackerFaction = attackerFaction;
        this.startedAt = startedAt;
    }
}
