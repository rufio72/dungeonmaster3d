# Analisi — "Dungeon Master" (cartella `Dungeon_Master/`)

> Obiettivo: capire **cosa** c'è in questa cartella e **se è possibile farlo
> girare su questa macchina Linux**. Aggiornato con l'esito della **build reale**
> (vedi §10): il motore CSBwin è stato **compilato, linkato ed eseguito con
> successo**. Manca solo l'aggiunta dei dati di gioco per giocare.

Data analisi: 2026-06-16 · Macchina: Ubuntu 24.04, g++ 13.3, x86_64.

---

## 1. Sintesi (TL;DR)

- La cartella **non è un singolo programma**, ma una raccolta di archivi di codice
  sorgente storici di *Dungeon Master* (FTL, 1987) e *Chaos Strikes Back*.
- Una sola parte è **realisticamente eseguibile su Linux**: **CSBwin**, il port
  C++/SDL multipiattaforma che reimplementa il motore e fa girare **sia Dungeon
  Master sia Chaos Strikes Back** leggendo i dati originali del gioco.
- **Verdetto: SÌ, è fattibile** far girare il gioco su questa macchina, con
  confidenza alta. Servono due cose che oggi **mancano**:
  1. la libreria di sviluppo **SDL2** (`libsdl2-dev`) — non installata;
  2. i **file dati del gioco** (`dungeon.dat`, `graphics.dat`, `mini.dat`, ecc.)
     — non inclusi nel sorgente (per ragioni di copyright), da scaricare a parte.
- Tutto il **codice sorgente C++ è presente e completo** (56 `.cpp` + header) e il
  toolchain di base (g++ 13.3, glib 2.80) c'è già.

---

## 2. Inventario della cartella

```
Dungeon_Master/
├── Dungeon_Master_FTL_Games_1987_Source_Code.7z   (3,2 MB, archivio non estratto)
├── ReDMCSB/                  reverse-engineering Atari ST (Megamax C + 68k asm)
└── csb/                      Chaos Strikes Back — varie versioni del port CSBwin
    ├── CSBwin12_3Source/
    ├── CSBwin_SRC_20160923/
    ├── CSBwin_SRC_20190702/  ← VERSIONE PIÙ RECENTE (luglio 2019) = target consigliato
    ├── Tool-CSBuild251Src/   editor di dungeon (Windows MFC)
    ├── CSBuild291Source/      editor di dungeon (Windows MFC)
    └── *.html                 documentazione/forum salvati
```
Totale ~2075 file.

### 2.1 Le tre "anime" del materiale

| Componente | Cos'è | Gira su Linux? |
|---|---|---|
| **`Dungeon_Master_..._1987_Source_Code.7z`** | Sorgente originale FTL del 1987: assembly 68000 per Atari ST. | ❌ No — valore storico. Richiederebbe un emulatore/cross-assembler Atari ST. |
| **`ReDMCSB/`** | Ricostruzione del sorgente in **Megamax C** (Atari ST) che produce un `.PRG`. | ❌ Non nativamente — è codice Atari ST (TOS/GEM), serve un emulatore ST. |
| **`csb/CSBwin*`** | **Port moderno C++/SDL** ("Chaos Strikes Back for Windows / Linux / Mac / Raspberry / Pocket PC"). | ✅ **Sì** — è questo il candidato per Linux. |

> Nota: nonostante il nome "CSB" (Chaos Strikes Back), il motore CSBwin è in grado
> di eseguire **anche Dungeon Master** (carica `dungeon.dat`/`DMGAME.DAT`). È
> l'unico modo pratico per "far funzionare Dungeon Master" su questa macchina
> partendo da questo materiale.

---

## 3. Il target buildabile: `csb/CSBwin_SRC_20190702/src`

Versione più recente (file datati fino a luglio 2019). Sorgente completo e
coerente: ho verificato che **tutti** i `.cpp` elencati nel `makefile` esistono.

### 3.1 Struttura e dipendenze

- **Linguaggio:** C++ (stile MSVC anni 2000 — c'è `stdafx.h`, precompiled header).
- **Entry point Linux:** `CSBlinux.cpp:1340` → `int main(int argc, char* argv[])`.
- **File piattaforma Linux:** `CSBlinux.cpp`, `LinScreen.cpp`, `LinCSBUI.cpp`
  (selezionati automaticamente da `makefile`/`CMakeLists.txt` su UNIX).
- **Astrazione nomi file** `LinuxFileName.inl`: prova varianti maiuscole/minuscole
  per gestire la **case-sensitivity** del filesystem Linux (i dati originali DOS/ST
  hanno nomi tipo `CSBGAME.DAT`). Buona notizia: il problema dei nomi è già
  mitigato nel codice.

### 3.2 Due sistemi di build disponibili

| Build | File | Backend grafico | Stato deps su questa macchina |
|---|---|---|---|
| **`makefile`** (consigliato) | target `Ubuntu_SDL20` | **SDL2** + glib-2.0 | glib ✅ · **SDL2 ❌** |
| `CMakeLists.txt` | CMake 2.4 | **SDL1** + (GTK2 opz.) + SDL_mixer opz. | SDL1 ❌ · GTK2 ❌ |

Il `makefile` punta esplicitamente a **SDL2** (`sdl2-config --libs`, define `SDL20`)
ed è la strada più moderna e diretta. Il `CMakeLists.txt` è più vecchio (SDL1 +
GTK2) e meno adatto a Ubuntu 24.04.

### 3.3 Output

Il `makefile` compila l'eseguibile in **`./CSB/CSBwin`** (richiede che la
sottocartella `CSB/` esista; al momento **non c'è** — va creata, una riga).

---

## 4. Stato del toolchain su questa macchina (verificato)

| Requisito | Stato | Note |
|---|---|---|
| g++ / gcc | ✅ 13.3.0 | OK |
| glib-2.0 | ✅ 2.80.0 | richiesto dal `makefile` |
| **SDL2 (`libsdl2-dev`)** | ❌ **assente** | **bloccante**: `stdafx.h` include `<SDL.h>`, quindi **nessun** file compila senza questa lib |
| SDL2_mixer | ❌ assente | opzionale (audio); senza, il gioco gira muto o con audio ridotto |
| SDL1 (`libsdl-dev`) | ❌ assente | serve solo se si usasse il path CMake (sconsigliato) |
| GTK2 | ❌ assente | solo opzionale nel CMake |

**Prova empirica fatta:** ho tentato la compilazione di `md5C.cpp` e `utility.cpp`
(file core senza grafica). Entrambi falliscono subito a
`stdafx.h:32: fatal error: SDL.h: File o directory non esistente`. Conferma che
SDL2 è l'unica dipendenza realmente bloccante per *iniziare* a compilare.

---

## 5. File dati di gioco (RUNTIME) — il secondo ostacolo

Il `README` di CSBwin è esplicito: per **giocare** servono questi file nella
directory di lavoro, **non distribuiti col sorgente** (copyright FTL/Software
Heaven):

```
dungeon.dat   graphics.dat   mini.dat   hcsb.dat   hcsb.hct   config.txt
```

Stato attuale nella cartella `src/`:

| File | Presente? |
|---|---|
| `hcsb.hct` | ✅ presente (66 KB) |
| `dungeon.dat` | ❌ mancante |
| `graphics.dat` | ❌ mancante |
| `mini.dat` | ❌ mancante |
| `hcsb.dat` | ❌ mancante |
| `config.txt` | ❌ mancante |

Dal codice risultano anche nomi alternativi gestiti: `DMGAME.DAT`,
`CSBGAME.DAT`/`CSBGAME2..4.DAT`, `CSBgraphics.dat`. Sono i dati delle due
avventure (DM e CSB).

→ I dati vanno scaricati dal sito di riferimento citato nel README,
**http://dmweb.free.fr** (CSB Encyclopaedia). Senza questi file l'eseguibile
**compila ma non parte / non carica il gioco**.

> ⚠️ Aspetto legale: il **motore** (CSBwin) è sorgente liberamente ridistribuito;
> i **dati di gioco** sono materiale protetto. L'uso lecito tipico è possedere
> l'originale o usare i dati resi disponibili dalla community a scopo di
> conservazione. Va tenuto presente, ma non è un ostacolo tecnico.

---

## 6. Rischi tecnici di compilazione (codice 2018-2019 su g++ 13)

Non verificabili a fondo finché SDL2 non è installata (ogni `.cpp` include
`stdafx.h`→`SDL.h`), ma in base alla natura del codice (MSVC-centrico, ~6-8 anni):

1. **C++ moderno più severo.** g++ 13 (default ~C++17) è più rigido del compilatore
   originale: probabili errori/warning su conversioni `char*` da string literal,
   narrowing, `register`, confronti signed/unsigned. Gestibili con
   `-fpermissive`/`-std=c++14` e piccole patch puntuali.
2. **`-fsigned-char`.** Il `CMakeLists` lo imposta apposta: il gioco assume `char`
   con segno (origine 68k/x86). Se si usa il `makefile`, conviene aggiungere
   `-fsigned-char` per sicurezza.
3. **API SDL.** Il path SDL2 nel makefile esiste già, ma alcuni file hanno blocchi
   `#ifdef SDL20`/`SDL12`: serve verificare che il percorso SDL2 sia completo
   (es. `LinScreen.cpp` vs `WinScreen.cpp`, gestione finestra/eventi).
4. **Audio.** `SoundMixer`/`Sound` dipendono da SDL_mixer o da backend specifici;
   senza `SDL2_mixer` il sonoro potrebbe essere disattivato (non bloccante per
   avviare il gioco).
5. **Cartella `CSB/` di output** mancante → il link finale fallisce finché non la
   si crea.

Stima: **errori di compilazione minori e localizzati, risolvibili**. Nessun
elemento suggerisce un blocco architetturale.

---

## 7. Relazione con questo repository (Blitz3D)

`Dungeon_Master/` è **estranea** al progetto Blitz3D in cui è stata depositata:
non è referenziata da `CMakeLists.txt`, `Makefile`, né dai sorgenti del compilatore
o del runtime. È materiale di studio/portabilità a sé stante. Compilarla **non**
interferisce con la build di blitzcc/runtime.

Attualmente la cartella risulta **non tracciata** da git (è nuova). Da decidere se
ignorarla (`.gitignore`) o tenerla fuori dal repo: per le sue dimensioni e per la
questione copyright dei dati, **non andrebbe committata** nel repo Blitz3D.

---

## 8. Piano per "farlo funzionare" (se si procederà — NON ancora eseguito)

Stima realistica: **fattibile in tempi brevi**. Passi proposti:

1. **Installare le dipendenze** (richiede privilegi/admin e rete):
   ```bash
   sudo apt install libsdl2-dev libsdl2-mixer-dev libglib2.0-dev
   ```
2. **Preparare l'output e compilare** la versione 20190702:
   ```bash
   cd Dungeon_Master/csb/CSBwin_SRC_20190702/src
   mkdir -p CSB
   make            # usa target Ubuntu_SDL20 (SDL2 + glib)
   ```
   Aspettarsi qualche errore di compilazione "vintage" da correggere
   puntualmente (vedi §6) — probabile aggiunta di `-fsigned-char` e/o
   `-std=c++14 -fpermissive` nelle `options` del makefile.
3. **Procurare i dati di gioco** (`dungeon.dat`, `graphics.dat`, `mini.dat`,
   `hcsb.dat`, `config.txt`) da dmweb.free.fr e metterli accanto all'eseguibile
   (`CSB/`), oppure nella cwd da cui si lancia.
4. **Avviare:**
   ```bash
   cd CSB && DISPLAY=:0 ./CSBwin      # finestra SDL2
   ```

### Possibile scorciatoia
Sul sito di riferimento esistono **binari Linux già pronti** di CSBwin. Se
l'obiettivo è solo *giocare* (non studiare/modificare il codice), usare il binario
ufficiale + i dati è la via più rapida. Compilare ha senso se si vuole
**modificare** il motore.

---

## 9. Conclusione

- **Cosa è:** raccolta di sorgenti storici DM/CSB; l'unica parte eseguibile su
  Linux è il port **CSBwin** (versione consigliata `CSBwin_SRC_20190702`).
- **Si riesce a farlo funzionare?** **Sì, con alta confidenza.** Il sorgente è
  completo, il toolchain c'è quasi tutto.
- **Due gap concreti da colmare:** (1) installare **SDL2** (+ mixer per l'audio);
  (2) ottenere i **file dati del gioco** (non inclusi).
- **Rischio residuo:** piccole correzioni di compilazione per codice del 2019 su
  g++ 13 — previste e gestibili, nessun blocco architetturale individuato.

Prossimo passo suggerito (su tua conferma): tentare la build reale della
20190702 dopo aver installato SDL2, e annotare/correggere gli errori effettivi.

---

## 10. ESITO DELLA BUILD REALE (eseguita 2026-06-16) ✅

**La build è riuscita e l'eseguibile gira.** Non è servito installare nulla:
abbiamo riusato l'**SDL2 già presente nel progetto Blitz3D**.

### 10.1 Cosa è stato usato al posto di installare SDL2
- Header SDL2 2.26: `deps/sdl/tree/include/` (bundled nel repo Blitz3D)
- Libreria statica: `_release/bin/x86_64-linux-gnu/lib/libSDL2.a` (già compilata)
- glib-2.0 2.80 di sistema (già presente)

→ Nessun `apt install` necessario. SDL2 è linkato **staticamente**; le librerie
di runtime che SDL apre dinamicamente (libX11, libasound, libpulse) sono già
presenti sul sistema.

### 10.2 Unica correzione necessaria
Codice del 2019 → g++ 13 è più severo sugli include impliciti. Diversi file usano
`memcpy`/`memset` senza includere `<cstring>` (primo errore in `SoundMixer.cpp`).
Risolto **senza modificare i sorgenti**, forzando gli header standard a livello di
compilazione con `-include cstring -include cstdio -include cstdlib`.
(In alternativa si potrebbe aggiungere `#include <cstring>` nei file interessati.)

Aggiunto anche `-fsigned-char` per sicurezza (il gioco assume `char` con segno).

### 10.3 Ricetta esatta (riproducibile)
```bash
cd Dungeon_Master/csb/CSBwin_SRC_20190702/src
mkdir -p CSB

SDLINC=/home/rufio72/blitx3d_linux/deps/sdl/tree/include
SDLLIB=/home/rufio72/blitx3d_linux/_release/bin/x86_64-linux-gnu/lib/libSDL2.a
GLIBCF="$(pkg-config --cflags glib-2.0) -I/usr/lib/glib-2.0/include"
FORCE="-include cstring -include cstdio -include cstdlib"

make -j"$(nproc)" \
  gincludes="-I$SDLINC $GLIBCF $FORCE" \
  glibs="$SDLLIB $(pkg-config --libs glib-2.0) -lpthread -ldl -lm"
```
Output: **`CSB/CSBwin`** — ELF64 PIE, 2,5 MB, dipendenze solo di sistema
(`libstdc++`, `libm`, `libc`, `libgcc`); SDL2 statico dentro.

### 10.4 Verifica di esecuzione
Lanciando `DISPLAY=:1 ./CSB/CSBwin` l'eseguibile stampa:
```
CSBwin 15.7v0
SDL initialized.
... Cannot find 'graphics.dat'
... CSBlinux will need the original datafiles for either Chaos Strikes Back,
    or Dungeon Master in your current working directory.
    dungeon.dat  hcsb.dat  hcsb.hct  mini.dat  graphics.dat  config.linux
```
→ Il motore **parte, inizializza SDL e raggiunge la logica di gioco**; si ferma
solo perché mancano i dati. È la conferma definitiva che build/link/runtime sono OK.

### 10.5 Cosa resta per GIOCARE
Mettere nella cwd dell'eseguibile (o passare i path da riga di comando, `--help`)
i file dati, **con nomi in minuscolo**:
```
dungeon.dat   graphics.dat   mini.dat   hcsb.dat   hcsb.hct(*)   config.linux
```
(*) `hcsb.hct` è **già presente** nel sorgente (va copiato accanto al binario).
Nota: il binario chiede `config.linux` (non `config.txt`). Gli altri `.dat` sono i
dati protetti FTL, da procurare separatamente (vedi §5). Senza di essi il gioco
non si avvia oltre questo punto — è l'unico elemento mancante, ed è esterno al
codice.

### 10.6 Cartella giocabile pronta ✅ (Dungeon Master)

I dati di gioco sono stati scaricati dalla community di preservazione
(dmweb.free.fr → pacchetto ufficiale *CSBwin / Dungeon Master*) e assemblati con
il motore compilato in:

```
Dungeon_Master/play_DungeonMaster/
├── CSBwin          (motore compilato in §10.3)
├── dungeon.dat     (livelli/mappa di Dungeon Master)
├── graphics.dat    (grafica)
└── config.linux    (mappatura tasti/mouse, con keypad SDL)
```

**Avvio:**
```bash
cd Dungeon_Master/play_DungeonMaster
DISPLAY=:1 ./CSBwin
```

**Verificato:** la finestra SDL si apre e mostra la **schermata del titolo di
Dungeon Master** (logo con i due occhi) — il gioco gira. Nessun errore a runtime.

> Nota dati: il pacchetto Linux di CSB fornisce solo `config.linux` + binario di
> riferimento (niente `.dat`); i dati di gioco veri provengono dal pacchetto
> Dungeon Master. I file `hcsb.dat`/`hcsb.hct`/`mini.dat` elencati nel messaggio
> d'aiuto sono opzionali (hint/CSB) e non servono per giocare a DM.
> Sorgenti scaricati conservati in `Dungeon_Master/downloads/`.

### 10.7 Stato finale
| Passo | Esito |
|---|---|
| Compilazione (56 TU) | ✅ OK (solo 1 fix di include, non invasivo) |
| Link (SDL2 statico, bundled) | ✅ OK |
| Avvio / SDL init / logica di gioco | ✅ OK |
| Dati di gioco (Dungeon Master) | ✅ scaricati e installati |
| **Gioco avviabile e renderizzante** | ✅ **schermata del titolo confermata** |

---

## 11. Fase 0 — Ponte stato-del-mondo (FATTA) ✅

Obiettivo: rendere la logica di gioco un componente **interrogabile** dall'esterno
(fondazione per 3D e multiplayer) senza toccare il core 68000.

- **`Bridge.cpp` / `Bridge.h`** (nuovi): leggono lo stato dal globale `d` (DBank)
  usando solo accessori esistenti del motore e scrivono `dm_world_state.json`
  ad ogni movimento del party. `CSBlinux.cpp` chiama `Bridge_Tick()` dopo ogni tick.
- **Esposto e validato dal vivo:**
  - party: `x, y, facing, level` (`d.partyX/Y/Facing`, `d.LoadedLevel`)
  - celle visibili (cono davanti): tipo muro/pavimento/porta/teleporter
    (`GetCelltypeRelative` + `RelativeXY`, additiva → seed con pos party)
  - mostri per cella (`FindFirstObject` + walk `GetDBRecordLink`, `dbMONSTER`)
  - personaggi: i 4 slot `d.CH16482[4]` (`CHARDESC`) con HP/Stamina/Mana,
    7 attributi (Forza, Destrezza, …), veleno, carico, inventario
- **Verifica:** in-dungeon il JSON riporta geometria reale (es. teleporter a (9,1));
  i champion risultano vuoti SOLO perché la demo d'attrazione non ha un party.

## 12. Audio — mixer SDL2 nativo (FATTO) ✅

La `libSDL2.a` bundled nel progetto Blitz3D è priva di backend audio
(solo dsp/dummy) → nessun suono e, con SDL_mixer assente, un crash a `0x0`.

- **`LinCSBUI.cpp`**: rimpiazzato il path SDL_mixer con un **mixer software a 8 voci**
  che usa l'audio nativo SDL2 (`SDL_OpenAudioDevice` + callback), con
  **interpolazione lineare** nel ricampionamento 6000→11025 Hz (no aliasing).
- **Fix bug latente**: `LIN_PlaySound` era `bool` senza `return` (UB → SIGILL/`0x0`).
- **SDL2 aggiornata**: si linka **dinamicamente** contro `libSDL2-2.0.so.0` 2.30
  (con alsa/pulse/pipewire), presa dal pacchetto Ubuntu ed estratta (no install),
  **bundle accanto al binario** con `rpath $ORIGIN`.
- **Esito:** audio funzionante e pulito su PipeWire.

## 13. Pacchetto esportabile (FATTO) ✅

`play_DungeonMaster/` è auto-contenuto e spostabile:
```
CSBwin              eseguibile (rpath $ORIGIN, linka libSDL2-2.0.so.0 accanto)
libSDL2-2.0.so.0    SDL2 2.30 con tutti i backend audio
build.sh            build riproducibile (compila + linka con rpath)
play.sh             avvio (nessuna env necessaria)
dungeon.dat graphics.dat config.linux   dati di gioco
dm_world_state.json  output live del ponte (Fase 0)
```
Gira senza `LD_LIBRARY_PATH`. Verificato: `RUNPATH=$ORIGIN`, SDL risolta dalla cartella.
