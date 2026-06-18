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

---

## Nota strategica — contenuti & monetizzazione (decisione futura)

Le **meccaniche** di DM sono libere; **mappe, livelli, mostri, personaggi, nomi,
storia** NO (espressione protetta). Sintesi delle opzioni:

| Opzione | Cosa | Verdetto |
|---|---|---|
| **A** | Engine + mappe **NUOVE tue**, vendibile (anche freemium 5+5) | ✅ pulito |
| **B** | Mappe DM **ritoccate** ("lievi modifiche") | ❌ lavoro derivato, NON valido |
| **C** | Engine generico "dungeon"; l'utente aggiunge i propri `.dat` e li carica | 🟡 stile-emulatore |

**Direzione attuale preferita: C + contenuti propri.** Pubblicare un gioco/**simulatore
di dungeon** con le NOSTRE mappe, e dare la possibilità di caricare file di livello
esterni (chi possiede i `.dat` di DM se li rigioca in 3D). Poi **multiplayer**.

Caveat per restare nella parte sicura di C (importante):
- Lo strumento/engine può essere legittimo (come un emulatore), ma **non** rende legali i
  dati DM: chi li carica deve possederli.
- **Non** marchiare/pubblicizzare il prodotto come "Dungeon Master" né venderlo *come mezzo
  per giocare DM* → rischio marchio + inducement. Si vende "un dungeon crawler/simulatore"
  con import generico di mappe.
- Il valore venduto sta nei **nostri contenuti**, non nell'accesso a DM.

(Non è consulenza legale: prima di pubblicare a pagamento, consulto con un legale IP.)

## Nota di design — engine DATA-DRIVEN (costruire gia' cosi')

Per tenere aperte tutte le opzioni, l'engine NON deve avere contenuto hardcoded:
- **mappe, mostri, oggetti, porte, decorazioni = caricati da FILE esterni** (formato nostro),
  non scritti nel codice.
- separare nettamente **motore** (rendering/fisica/regole) da **contenuto** (dati livelli).
- definire un **formato di livello** documentato → consente sia le nostre mappe sia un
  import/convertitore per dati di terzi (come fa gia' il ponte CSBwin oggi).
- `dm3d.bb` oggi legge gia' mappa/stato dai file del ponte: e' il primo passo data-driven;
  il prossimo e' un **formato di livello proprio**, indipendente da CSBwin.
- bonus: il data-driven abilita editor di mappe, contenuti scaricabili (DLC) e — piu' avanti
  — il **multiplayer** (stato di gioco condiviso).

---

## Rilievo muri/pavimento — analisi & direzione (2026-06-18)

Obiettivo: muri (e pavimento) non piatti, con **solchi in rilievo tra i mattoni**.

### Bump/normal mapping "vero": perche' NON e' una patch (verificato sul codice)
- `blitz3d.gl/default.glsl`: l'illuminazione e' **per-VERTICE** (loop luci nel *vertex*
  shader → `bbVertex_Color`); il fragment campiona solo texture+fog. Il bump richiede
  luce **per-pixel** → va spostato il modello di luce nel fragment.
- `GLVertex` (blitz3d.gl.cpp:54) = coords/normal/color/tex0/tex1 → **nessuna tangente**,
  e nessuna generazione tangenti nel codice. `BLEND_DOT3` esiste ma non e' implementato.
- Servirebbero 4 strati: (A) luce per-pixel, (B) tangenti [formato vertice+generazione],
  (C) binding normal map [API+UBO], (D) sample+TBN nel fragment, (E) asset normal map.
  Progetto engine multi-strato, tocca TUTTO il 3D (rischio regressioni).
- **Scorciatoie**: normal map **object-space** salta le tangenti (B) — i muri sono statici
  e allineati agli assi, basta `bbNormalMatrix` (gia' c'e'). E la sola **luce per-pixel (A)**
  migliorerebbe gia' molto muri/pavimento (oggi quad a 4 vertici → quasi nessun gradiente).

### Scelta attuale: GEOMETRIA riusata (zero modifiche al runtime) — IN PROVA
Una **mesh "pannello di rilievo"** generata una volta (mattoni proud / solchi malta
incassati), texturizzata col muro, **istanziata** (`CopyEntity`) sulle facce di muro
visibili (verso corridoio). Stesso concetto per il pavimento (lastroni + fughe).
Pro: rilievo vero che cattura luce/nebbia, nessun rischio engine. Contro: qualche
poligono in piu' (gestito dall'instancing). Se convince si tiene; il bump vero (A+
object-space normal map) resta opzione futura se si vuole spingere oltre.
