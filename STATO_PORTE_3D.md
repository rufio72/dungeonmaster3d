# Stato lavori — Porte nel viewer 3D (Dungeon Master)

> Dove siamo e come continuare. Aggiornato: 2026-06-18.
> Vedi anche `ANALISI_PORTE.md` (progetto/analisi iniziale) e `README.md`.

## In breve
Il viewer 3D (`play_DungeonMaster/dm3d.bb`) ora disegna le porte come **geometria
3D** (non sprite DM), pilotate dallo stato del ponte CSBwin. Le grafiche originali
DM sono state estratte come **riferimento stilistico**, ma NON usate come texture
(sono microscopiche, 16-96 px → in 3D fanno schifo). Aggiunto anche **MSAA 4x** nel
runtime per togliere lo shimmer dei bordi sottili.

## Fatto ✅
- **Fase A — ante animate**: il ponte (`Bridge.cpp::EmitDoorsText`) esporta per ogni
  porta `x,y,state,type` in `dm_state.txt`; il viewer fa scorrere l'anta verso l'alto
  (portcullis) interpolando, orientata dai muri adiacenti.
- **Porta di ferro a grata** in geometria (`BuildIronDoor`): reticolo di barre sottili
  con i buchi (si vede attraverso), bulloni sporgenti agli incroci, texture metallo
  scura+ruggine (`dm_iron.png`), specularità bassa. Dimensioni grata ~0.75×W / 0.88×H
  del vano, centrata.
- **Porta di legno** in geometria (`BuildWoodDoor`): lastra + bande di ferro + borchie,
  texture legno (`dm_wood.png`). Usata per `doorType=1`.
- **Stipiti in pietra** (`BuildDoorFrame`): due pali laterali a contatto col muro del
  corridoio (~28% larghezza), niente architrave. Texture muro con UV scalate (mostra una
  fetta a scala giusta, niente schiacciamento/repeat).
- **Dispatch per tipo** (`BuildDoor`): `doorType` 0→ferro, 1→legno (letto una volta da
  `dm_state.txt` in costruzione, default ferro).
- **Camera**: movimento fluido tra gli step (`SMOOTH=0.15`) + **idle sway** (da fermo la
  camera "respira"/dondola leggermente, si attenua mentre cammini).
- **MSAA 4x** nel runtime OpenGL (commit `f16be58` nel repo blitx3d_linux): bordi lisci.
  Verificato OK con SaveBuffer e riflessi cubemap.
- **Grafiche DM estratte** (solo riferimento, in `doors/ jambs/ buttons/` — NON nel repo,
  arte protetta): 12 ante, 8 stipiti, 56 dispositivi (leve/pulsanti/serrature). Export nel
  ponte ora **disabilitato** (righe commentate in `Bridge_Tick`); decoder: `*_decode.py`.

## Da fare ⬜
1. **Stile reale per livello**: ora `doorType` 0/1 → ferro/legno fisso. Mappare al vero
   stile DM del livello (da `DoorGraphic[][]` nel ponte) così ogni porta ha l'aspetto giusto.
2. **Porta "fulmine"** (stile magico DM): da fare come pannello emissivo/glow, non geometria.
3. **Fase A+ — dispositivi sul muro**: estendere il ponte a esportare gli attuatori
   (cella+lato+tipo+porta-bersaglio, riusando lo scan dei ritratti) e disegnare
   bottone/leva/serratura sul muro come i ritratti. Poi eventualmente interazione (Fase B).
4. **Rifinitura estetica** grata/legno (texture, proporzioni) quando si vuole.

## Come riprendere (build & run)
```bash
cd play_DungeonMaster
python3 gen_textures.py          # (ri)genera dm_wood.png / dm_iron.png (procedurali, ns)
./build.sh                       # ricompila CSBwin col ponte
blitzcc -o dm3d dm3d.bb          # ricompila il viewer (serve blitzpath=_release)
DISPLAY=:1 ./CSBwin              # gioco 2D (auto-entra nel dungeon, scrive i file del ponte)
DISPLAY=:1 ./dm3d                # viewer 3D (segue il party)
```
Note: display reale `:1` (con `:0` SaveBuffer legge nero). Per ri-estrarre le grafiche DM
si riabilitano le righe `*_Done` in `Bridge_Tick` (poi ri-commentare).

## File chiave (nostri)
- `play_DungeonMaster/dm3d.bb` — viewer 3D (porte, camera, idle sway, MSAA-ready).
- `csb/CSBwin_SRC_20190702/src/Bridge.cpp` — ponte: stato porte per-tick + estrazione (disab.).
- `play_DungeonMaster/gen_textures.py` — genera le texture procedurali.
- `play_DungeonMaster/{doors,buttons,jamb,gfx}_decode.py` — decoder grafiche DM (riferimento).
