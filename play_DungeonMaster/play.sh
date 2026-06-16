#!/usr/bin/env bash
# Avvia Dungeon Master (motore CSBwin) dalla cartella di gioco.
# Pacchetto auto-contenuto: la libSDL2 e' caricata via rpath $ORIGIN, nessuna
# variabile d'ambiente necessaria. I file dati (dungeon.dat, graphics.dat, ...)
# devono stare in questa stessa cartella.
cd "$(dirname "$0")"
exec ./CSBwin "$@"
