# Dungeon Master 3D — ponte + visualizzatore

Lavoro sperimentale sopra il motore **CSBwin** (clone open di Dungeon Master /
Chaos Strikes Back): un "ponte" che espone lo stato di gioco e un visualizzatore
3D che lo legge. Cervello (CSBwin) → ponte → corpo (renderer Blitz3D).

Dettagli completi: vedi **`ANALISI_DUNGEON_MASTER.md`**.

## Cosa contiene questo repo (solo il nostro lavoro)
- `csb/CSBwin_SRC_20190702/src/Bridge.cpp` / `Bridge.h` — il **ponte** (Fase 0):
  scrive mappa del livello + stato (party, personaggi, mostri) in JSON e .txt.
- Modifiche a `CSBlinux.cpp` (hook del ponte), `LinCSBUI.cpp` (mixer audio SDL2
  nativo + fix), `makefile` (build del ponte).
- `play_DungeonMaster/dm3d.bb` — il **visualizzatore 3D** (Fase 1, Blitz3D).
- `play_DungeonMaster/build.sh` / `play.sh` — build e avvio.

## Cosa NON è nel repo (vedi `.gitignore`) e come ottenerlo
- **Dati di gioco** (`dungeon.dat`, `graphics.dat`, `config.linux`, ...): materiale
  protetto FTL/Software Heaven. Scaricabili dalla community di preservazione
  (dmweb.free.fr) — pacchetto Dungeon Master / CSBwin.
- **SDL2 con audio** (`libSDL2-2.0.so.0`): una libSDL2 con backend audio
  (alsa/pulse/pipewire), affiancata all'eseguibile.
- **Sorgenti CSBwin completi**: scaricabili a parte; qui ci sono solo i file
  modificati. Binari, archivi, texture derivate e output runtime sono esclusi.

## Build & run (riassunto)
```bash
cd play_DungeonMaster
./build.sh        # compila CSBwin con il ponte (serve la libSDL2 con audio)
./play.sh         # avvia il gioco; il ponte scrive dm_level_*.txt / dm_state.txt
./dm3d            # avvia il visualizzatore 3D (legge i file del ponte)
```
