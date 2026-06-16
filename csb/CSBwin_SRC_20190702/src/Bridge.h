// Bridge.h - Phase 0 "world-state bridge" for CSBwin.
//
// Espone lo stato di gioco (party, celle visibili, mostri) verso l'esterno
// SENZA toccare la logica 68000. Pensato come fondazione per un renderer 3D
// e/o un layer di rete: il "cervello" (CSBwin) resta intatto, qui lo si legge.
//
// Bridge_Tick() va chiamato dal game loop dopo ogni UIM_TIMER. Fa un dump solo
// quando lo stato osservabile cambia (movimento/rotazione/cambio livello),
// scrivendo dm_world_state.json nella cwd e una riga di sintesi su stdout.

#ifndef BRIDGE_H
#define BRIDGE_H

void Bridge_Tick(void);

#endif // BRIDGE_H
