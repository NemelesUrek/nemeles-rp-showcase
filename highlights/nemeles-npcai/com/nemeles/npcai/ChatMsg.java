package com.nemeles.npcai;

/** Un mensaje del historial de conversación (rol = system | user | assistant). */
public record ChatMsg(String role, String content) {}
