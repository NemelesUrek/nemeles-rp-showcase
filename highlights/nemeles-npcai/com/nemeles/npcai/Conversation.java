package com.nemeles.npcai;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/** Estado de una conversación de un jugador con un NPC. Se muta solo en el hilo principal. */
public final class Conversation {
    public final NpcPersona persona;
    public final UUID entityId;            // entidad del NPC (para comprobar proximidad)
    public final Deque<ChatMsg> history = new ArrayDeque<>();
    public long lastActivity;
    public boolean busy;                   // hay una respuesta en curso
    public boolean retriedLang;            // ya se reintento por respuesta en idioma equivocado (1 sola vez)
    public long cooldownUntil;
    public String memory = "";             // notas que el NPC recuerda de este jugador (cargadas al empezar)
    public int userTurns = 0;              // nº de mensajes reales del jugador (para decidir si vale recordar)
    public boolean opened = false;         // ya se entregó el saludo casual inicial
    public String actionNote;              // accion fisica REAL recien ocurrida (seguir/regalo/echar); se inyecta 1 vez al prompt

    public Conversation(NpcPersona persona, UUID entityId, long now) {
        this.persona = persona;
        this.entityId = entityId;
        this.lastActivity = now;
    }
}
