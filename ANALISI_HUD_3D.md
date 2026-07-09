# Analisi — HUD del party nel viewer 3D (barra campioni, marching order, mano, magie)

> Richiesta: nel 3D mancano gli elementi che il 2D mostra quando un campione
> entra nel party (vedi screenshot con ELIJA reclutato):
> 1. la **barra in alto** con l'elenco dei campioni (nome, barre verdi
>    HP/STAMINA/MANA, le due mani con gli oggetti impugnati);
> 2. il **quadrato con la posizione dei PG** nel party (marching order);
> 3. la **mano** sopra i tasti di movimento (pannello d'attacco: pugno o arma);
> 4. la **barra delle magie** (compare quando c'è un caster).
>
> Domanda specifica: la barra campioni va in una **barra nera dedicata in
> alto** (finestra più alta) o in **sovraimpressione sul 3D**?
>
> Data analisi: 2026-07-03. Solo analisi: nessuna modifica al codice.
> Prerequisito: schermata inventario/specchio già funzionante
> (ANALISI_SCHEDA_CAMPIONI.md, §5 stato implementazione).

---

## 1. Barra nera dedicata vs sovraimpressione — analisi e raccomandazione

**Raccomandazione: barra nera dedicata in alto** (d'accordo con la proposta).
Motivazioni, in ordine di peso:

1. **È l'architettura del 2D stesso.** DM non sovrappone MAI l'HUD alla
   scena: lo schermo 320x200 è diviso in zone fisse — barra campioni in alto
   (y 0..32, fuori dal viewport), viewport 3D 224x136 a (0,33), colonna
   comandi a destra (x 224..319). Il nostro viewer replica già questo schema
   con la barra destra (BAR_W=200): la barra in alto è il completamento
   naturale, non un'eccezione.
2. **Leggibilità garantita.** Il dungeon ha pareti chiare e corridoi neri:
   qualunque overlay avrebbe contrasto variabile (testo bianco su muro
   illuminato dalla torcia = illeggibile). Su fondo nero i box campioni
   (che nel 2D hanno fondo proprio grigio/colore) restano sempre leggibili.
3. **Niente occlusione della scena.** In alto nel 3D ci sono soffitto e
   fondo del corridoio: coprirli con 4 box da ~80px alteri la percezione
   dello spazio (le porte/scale lontane spariscono dietro l'HUD).
4. **Click non ambigui.** I click sulla barra non entrano mai in conflitto
   con il CameraPick del viewport (ritratti, porte, in futuro oggetti):
   la separazione per zone elimina l'hit-test a priorità.
5. **Costo tecnico basso.** Blitz3D: si alza la finestra e si sposta il
   viewport — `Graphics3D 840, 480+BARH` e `CameraViewport camera, 0, BARH,
   VIEW_W, 480`; il pannello inventario e il pad slittano di BARH. Nessun
   render-to-texture, nessuna trasparenza.

La sovraimpressione resta utile SOLO per elementi transitori legati alla
scena (es. danno fluttuante, nomi dei mostri): non è questo il caso.

### 1.1 Geometria proposta

- Nel 2D ogni box campione è **69x28 px** + la striscia sotto per il
  pos/dir (i box partono a x = 69*slot, `DrawCharacterState`,
  `Character.cpp:1249-1262`).
- **Scala**: alla scala 2.75 del pannello inventario, 4 box = 4*190 = 759 px:
  NON stanno nei 640 del viewport 3D, ma **stanno nella larghezza totale
  della finestra (840)**. Proposta: barra a **tutta finestra** (840xBARH),
  4 box a scala 2.75 (coerente con l'inventario) + a destra, nello spazio
  residuo (~80px), il **quadrato marching-order** — che così vive proprio
  sopra la colonna comandi, vicino a dove sta nel 2D.
- **BARH**: 28*2.75 ≈ 77 px + margine → **88 px** (finestra 840x568).
  Alternativa compatta: scala 2 (4 box = 552 px dentro i 640, BARH 64) se
  si preferisce non toccare la larghezza — ma le icone delle mani (16px)
  a scala 2 sono più piccole di quelle dell'inventario: meno coerente.

---

## 2. Anatomia dell'HUD nel 2D (riferimenti verificati nel sorgente)

### 2.1 Box campione (barra in alto)
`DrawCharacterState` (`Character.cpp:1230-1503`) disegna, per il campione
`i`, nel rettangolo (69*i, 0)-(69*i+66, 28):

- **nome** (`TextOutToScreen`, colore 9 se il campione possiede il cursore
  mano, 13 normale; su campo pieno se inventario aperto il box mostra invece
  il **mini-ritratto** — `CharacterPortraitToStatusBox`, `Character.cpp:4657`);
- **3 barre verticali HP/STAMINA/MANA** (`DrawLifeBars`,
  `Character.cpp:911`): altezza `ceil(25 * cur/max)` px su scala 25,
  colore verde (rosso se avvelenato — da verificare il campo in
  implementazione);
- **le due mani** = slot 0 e 1, disegnate da `DisplayBackpackItem` con
  `squareNumber = 2*chIdx + mano` → posizioni `IconDisplay[0..7]`
  (già esportate in `invgfx/icondisplay.txt`: (4,10),(24,10),(73,10)...),
  cornici 33/34/35 e segnaposto come nell'inventario;
- **scudi** attivi: icone 37 (party/personale), 38 (fire), 39 (spell)
  sovrapposte al box (`Character.cpp:1266-1287`) — blit con **colorkey
  indice 10** (4° parametro di `BLT2Screen`; stesso schema del 6 di g40:
  da aggiungere a `gfx_decode.py`);
- campione **morto**: box grafica **8** + nome (`Character.cpp:1303`),
  blit **opaco** (param -1);
- slot **vuoto**: rettangolo nero.

Note di implementazione verificate:
- le **posizioni delle 3 barre** non sono costanti nel codice ma tabelle
  dati con maschere dither (`d.Word1526[4][3]` offset, `d.Word1502` maschere,
  `DrawLifeBars` → `TAG014a60`): non conviene esportarle — si replicano a
  vista dal 2D (colonne ~4px a passo 6 sul lato destro del box, altezza
  0..25 dal basso, verde);
- il **mini-ritratto** è blittato nel rect (69i+7, 0)-(69i+38, 28)
  (`CharacterPortraitToStatusBox`, opaco): **copre nome e mani** — a
  inventario aperto il box è mini + barre;
- il CHARDESC **non conserva l'ordinale del ritratto** (solo i pixel copiati
  dall'atlante in `AddCharacter`): per sapere QUALE `hud_NN.png` mostrare,
  il bridge ricava l'ordinale **dal nome**, confrontando con le schede dei
  24 specchi (stessa scansione del comando `CHAMP`, cache per livello);
  `-1` se non trovato → il 3D ripiega sul nome testuale;
- anche la **freccia g28** del marching order usa un colorkey (**indice
  12**, ultimo param di `TAG0088b2`), da aggiungere a `gfx_decode.py`;
  8/28/37/38/39 vanno anche nella lista FLIPPED (verranno ritagliati con
  `DrawImageRect`).

Tutte le grafiche necessarie (8, 28, 33-35, 37-39, atlanti 42-48) sono
**già esportate** in `invgfx/`.

### 2.2 Quadrato marching-order
UN riquadro con 4 celle (`d.wRectPos1454[4]`, `Data.h:1417`): la cella del
campione è `wRectPos1454[(charPosition+4-partyFacing)&3]`, riempita col
**colore del campione** `d.Byte1386[chIdx]` (`Data.h:1425`) e sovrapposta
la **freccia di facing** dalla grafica **28** (celle da 19px, colonna =
`(facing+4-partyFacing)&3`) — `Character.cpp:1460-1477`. È RELATIVO al
party: ruota quando il party gira. Da esportare a runtime: i 4 rect + i 4
colori (stesso meccanismo di `layout.txt`).

### 2.3 La "mano" = pannello d'attacco
`DrawAttackButton` (`Character.cpp:4830`): un pannello **19x34** per
campione a `x = 233 + 22*chIdx`, y 86..120 (colonna destra). L'icona è
l'**arma nello slot 1** rimpicciolita (`ShrinkBLT`), oppure il **pugno**
(`objNI_Special_c`, dagli atlanti già esportati) a mano vuota; se l'arma
non ha `AttackClass` → riempimento pieno. Il pannello è **ombreggiato**
(disabilitato) con `CHARFLAG_attackDisable`, provvisorio pendente o party
addormentato. Il click apre il menu delle azioni dell'arma (parry/chop/...):
è un'interazione a stati (menu → scelta → cooldown), da trattare in una
fase dedicata.

### 2.4 Barra delle magie
`DrawSpellControls` (`Character.cpp:4997`): area in alto nella colonna
destra (`d.Word20242` = rect, nomi caster a y=48). Mostra il nome del
**caster selezionato** (`d.MagicCaster`), le **6 rune** della scuola con
la potenza, la spell in composizione e il backspace. Click:
- selezione caster: codici coda **125-128** → `TAG0025a6(idx)`
  (`Mouse.cpp:1626-1637`) — iniettabili senza coordinate;
- click sulle rune/backspace: codice **100** → `HandleMagicClick(x, y)`
  (`Mouse.cpp:1672-1680`, guardia `MagicCaster != -1` e niente provvisorio)
  — richiede le **coordinate reali** dei bottoni-runa (stesso schema del
  codice 81 per RRC: esportare i rect e iniettare il centro).
La composizione della spell (rune scelte, potenza) sta nel CHARDESC del
caster: campi esatti da individuare in implementazione (`HandleMagicClick`).

### 2.5 Click già mappati sulla coda mouse (riepilogo per il 3D)
| Codice | Azione | Note |
|---|---|---|
| 7-10 | apri/chiudi inventario campione | GIÀ usato dalla X del pannello |
| 12-15 | click sul box campione (`TAG019c34`) | selezione/riordino party, da approfondire |
| 21-27 (da verificare) | mani nei box in alto | prendere/posare col cursore-mano |
| 70 / 71 | bocca (cibo) / occhio (attributi) | premuti-continui: gestire press/release |
| 100 | rune magia (con x,y) | come RRC: serve il rect |
| 111 | menu azioni attacco (`TAG019fac` → `Buttons17892`, 112=`ExecuteAttack`) | CONFERMATO (Character.cpp:4674-4705) |
| 125-128 | seleziona caster | senza coordinate |

---

## 3. Dati nuovi dal bridge (push "CHAMPS")

Lo STATE/push testuale oggi non contiene i campioni (solo il JSON legacy li
ha). Proposta: nuova sezione in coda a `EmitStateText` (append-only, come
le 4 righe inventario — i client vecchi non si rompono):

```
4                       # numero slot
per ogni slot (4):
  active (0/1)
  name
  hp / maxHp / stamina / maxStamina / mana / maxMana
  dead (0/1), poison (0/1)
  charPosition (0-3)    # cella marching order
  colore (indice Byte1386)
  mano0_NI / mano0_frame, mano1_NI / mano1_frame   # come SHEET
  attackIcon_NI (-1 = pannello pieno), attackDisabled (0/1)
shields: fire/spell/party (3 valori globali+per-char shieldStrength)
HandChar, objectInHand_NI       # chi ha il cursore-mano e cosa tiene
MagicCaster + stato spell (fase magie)
```

Grafiche/metadati aggiuntivi da esportare: `wRectPos1454[4]`, `Byte1386[4]`,
rect della spell bar e dei suoi bottoni, rect dei pannelli attacco
(calcolabili: 233+22*i). Gli atlanti/cornici/icone ci sono già.

---

## 4. Piano di implementazione proposto — **Fase HUD-1 FATTA il 2026-07-03**

> Stato: barra nera 840x88 in alto (finestra 840x568, viewport a y=88),
> sezione CHAMPS nel push (4x17+6 righe in coda, incluso facing), box
> campioni con nome, barre, mani, morto (g8), scudi (37-39) e mini-ritratto
> ORIGINALE DM (`portraits/hud_NN.png`, ordinale via lookup nome→specchio)
> a inventario aperto. Verificato end-to-end con RESURRECT via TCP. La X
> del pannello sui campioni confermati usa `CMD 7+idx`.
>
> **Aggiunte del 2026-07-03 (richieste utente):** click sinistro O destro
> sul box campione della barra → apre/chiude il suo inventario (`CMD 7+i`);
> pannello inventario con FOOD/WATER (g20/g30/g31/g32, barre come
> `DrawFoodWaterBar`, Viewport.cpp:7223: x=113, food y=69, water y=92,
> lunghezza `(v+1024)/32`, giallo se <0, rosso se <-512); pad movimento a
> scheda aperta NON nascosto ma SCURITO e inattivo (`mvbuttons_dark.png`).
>
> **Fase HUD-2 FATTA il 2026-07-03:** quadrato marching-order in cima alla
> colonna destra — celle dai rect `wRectPos1454` esportati (18x13 a passo
> 20/15, ordinamento orario), riempite col colore campione (`Byte1386` +
> palette ST) e figurina di facing g28. NOTA: la figurina è affettata in
> 4 celle-PNG separate (`g28c0..3.png`, meccanismo SLICES di gfx_decode) e
> disegnata con DrawImage: DrawImageRect è una copia raw senza alpha e
> coprirebbe il colore-cella. Il quadrato NON ruota girando il party: il
> motore rimappa `charPosition` per conservare la formazione (verificato).
> Gotcha Blitz scoperto: le funzioni NON vedono i locali del main (pf va
> passato come parametro) e gli And NON corto-circuitano (mai indicizzare
> array nella stessa condizione che ne valida l'indice).
>
> **Fase HUD-4 FATTA** (barra magie: linguette caster, rune, incantation,
> backspace/cast; comando `SPELL <n>` nel ponte — vedi `DrawMagicBar`/
> `MagicUI` in dm3d_ui.bb).
>
> **Fase HUD-3 FATTA il 2026-07-09:** pannello attacco sotto la barra magie
> (stessa scala MAGSC, origine MagX/MagY):
> - **Ponte**: blocco ATTACK in coda alla sezione CHAMPS del push (per slot
>   icona come `DrawAttackButton` — -2 morto, -1 riquadro pieno, NI arma o
>   pugno — + flag disabled; poi Word20252, ordinale attaccante, n. opzioni
>   e 3 nomi azione via `GetAttackTypeName`+`ActionNameFilter`); export in
>   layout.txt dei rect del menu (`amenu1..3` = wRectPos20218/10/02) e delle
>   button-list `abutton` (Buttons17952, 116-119) / `obutton` (Buttons17892,
>   112 pass + 113-115 opzioni); grafica 10 (pannello menu) in gfxList;
>   comando TCP `ATTACK <n>` che inietta il codice coda 111 sul centro del
>   rect giusto (stessa filosofia di SPELL/RRC).
> - **Decoder**: gfx_decode.py emette anche varianti a scala 2 (`g<N>_2.png`
>   / `_2f`) per g10 e atlanti 42-48 (tabella SCALE2) — la colonna destra
>   sta a MAGSC=2, gli atlanti principali a 2.75 non erano riusabili.
> - **Viewer**: `DrawAttackPanel` (pannelli 20x35 ciano con finestra icona
>   nera 16x16, morto=nero, retino shade.png se disabilitato; menu = g10_2f
>   ritagliato al rect, nome NERO su banda ciano baseline 83, opzioni CIANO
>   baseline 93+12*i) e `AttackUI` (hit-test sui rect delle button-list,
>   invia `ATTACK <code>`). Cooldown dopo l'attacco = disabled dal 2D,
>   nessuno stato locale: il viewer disegna solo cio' che dice il push.
> - **Bonus**: comando TCP `START` (211 = DUNGEON nel menu master, `START
>   200` = Enter Prison nel titolo) — avvia la partita senza click sulla
>   finestra 2D; rifiutato a livello caricato. I menu di CSBwin leggono la
>   stessa coda mouse del gioco, con voci a codice diretto.
> - Verificato end-to-end via TCP (recluta col MIRROR/RRC, ATTACK 116 apre
>   il menu SLASH/PARRY, 112 passa, 113 attacca con cooldown ~0.5s) e
>   visivamente nel viewer.
>
> Resta (fase dedicata): drag col cursore-mano sulle mani dei box (21-27),
> click 12-15 sul box campione (riordino party), name-entry.

1. **Fase HUD-1 — barra nera + box campioni (read-only)**: finestra
   840x568, viewport a y=88; bridge: sezione CHAMPS nel push; dm3d: 4 box
   (nome, 3 barre, 2 mani con cornici/segnaposto, morto=g8, scudi 37-39,
   **mini-ritratto** `NN_tiny.png` quando l'inventario è aperto — vedi §6);
   più `gen_thumbs.py` una tantum. Il grosso del valore visivo con zero
   nuove interazioni. (~120 righe bridge, ~170 dm3d, ~30 python)
2. **Fase HUD-2 — marching order + click sui box**: quadrato 2x2 a destra
   nella barra (rect/colori esportati, freccia g28); click sul box → codici
   7-10 (inventario, già supportati) e 12-15 (da approfondire `TAG019c34`).
3. **Fase HUD-3 — pannello attacco ("la mano")**: icona arma/pugno per
   campione nella colonna destra sopra il pad (fedele al 2D), stato
   disabilitato; click → menu azioni (analisi dedicata al menu a stati).
4. **Fase HUD-4 — barra magie**: caster tabs (125-128), rune con rect
   esportati (codice 100 con coordinate), stato spell nel push.

Ordine pensato per valore/rischio: 1 e 2 sono meccanici; 3 e 4 introducono
interazioni a stati e meritano una verifica in corso d'opera.

---

## 5. Domande aperte — **DECISE il 2026-07-03**

1. **Larghezza barra**: ✔ a tutta finestra (840, scala 2.75, marching-order
   a destra nella barra).
2. **Colonna destra**: ✔ barra magie sopra pannello attacco e pad, layout
   fedele al 2D.
3. **Mani nei box**: ✘ per ora NO drag col cursore-mano — le mani si vedono
   (icone + cornici) ma non trascinano; rimandato a una fase dedicata.
4. **Mini-ritratto** nel box a inventario aperto: ✔ sì, si replica subito
   (vedi §6 per QUALE immagine usare).

---

## 5-bis. Fedeltà del box campione — analisi del 2026-07-03 (confronto 2D/3D)

Confronto pixel-per-pixel (screenshot 2D misurati) + verifica nel codice.
Quattro difetti confermati nel 3D attuale:

1. **Barre vitali fuori posto**: nel 2D reale stanno a x = **46 + b*7**,
   larghe **4**, y 2..27 (misurato: colonne finestra 92-99/106-113/120-127,
   cioè gioco 46-49/53-56/60-63). Il 3D le disegna a x = 41 + b*7 larghe 5:
   partono 5px (gioco) troppo a sinistra e sono più larghe → la spaziatura
   con le mani e fra le barre non torna. Fix: replicare 46/7/4.
2. **Manca la targhetta del nome**: nel 2D sotto il nome c'è un rettangolo
   **grigio chiaro** (0,0)-(42,6) riempito col colore palette 1 =
   (109,109,109) (`DrawCharacterState`, nameRect + FillRectangle), e il
   testo è **arancio** (palette 9 = 255,182,0) se il campione possiede il
   cursore-mano (`d.HandChar`), grigio chiaro (13 = 182,182,182) altrimenti
   — misurato (255,165,0) nello screenshot ✓. Il 3D scrive in bianco senza
   targhetta.
3. **Font troppo piccolo**: le lettere 2D sono alte 6px → alla nostra scala
   2.75 dovrebbero essere ~16px; il font di sistema di `Text` è ~11-13px.
   Due strade: (a) rapida — `LoadFont` di un TTF a ~16-17px; (b) autentica —
   estrarre il **font originale DM** (768 byte, `Pointer12926`, glifi 6x6/8
   in layout colonna-per-128-caratteri, `Graphics.cpp:339`/`BltOneChar`):
   il bridge può dumparlo con lo stesso `ReadAndExpandGraphic(0x822d)` e il
   3D disegnarlo come atlante glifi. (b) è la scelta giusta per la fedeltà
   di TUTTI i testi del pannello, come fase dedicata.
4. **Quadrato marching-order**: nel 2D sta **attaccato al bordo superiore**
   (rect a y=1, x=281..319 = filo del bordo destro dello schermo) e NON ha
   nessun riquadro/fondo più chiaro: le celle vuote sono semplicemente nere
   (invisibili). Il 3D lo disegna sotto la barra (y=BARH+10) con un backdrop
   grigio scuro visibile — va spostato in alto a destra DENTRO la barra
   (y≈3, filo del bordo destro) e senza backdrop. Inoltre la figurina
   affettata è 19x14 ma la cella è 18x13: il DrawImage sborda di ~3px
   scalati — le celle vanno affettate a 18x13 (pitch 19).

## 6. Ritratti multi-dimensione (analisi richiesta)

> Osservazione: l'immagine del campione serve in usi con dimensioni
> radicalmente diverse — non ha senso caricare una foto ~1000x1000 per un
> riquadro da ~40 px. Va duplicata in tagli dedicati.

### 6.1 Gli usi e le dimensioni reali

| Uso | Dimensione a schermo | Asset giusto |
|---|---|---|
| Quadro sul muro nel 3D (texture) | variabile, anche da vicino | `NN_sm.png` 512x512 (attuale) — corretto: è una texture 3D con mipmap, vista anche a tutto schermo |
| **Mini-ritratto nel box campione** (HUD, `CharacterPortraitToStatusBox`: 32x29 px 2D → **88x80** a scala 2.75) | 88x80 fissi | **nuovo `NN_tiny.png` 88x80**, generato offline |
| (futuro) primo piano nella scheda/dialoghi | fino a ~400px | `NN_sm.png` 512, caricato **lazy** solo quando serve |
| Master di lavorazione | mai caricato dal viewer | `NN.png` 1024 (resta solo sorgente per generare gli altri) |

### 6.2 I numeri (misurati sugli asset attuali)

- 24 ritratti rifatti: `NN.png` 1024x1024 ≈ **31 MB** su disco;
  `NN_sm.png` 512x512 ≈ **9.6 MB** su disco ≈ **24 MB di VRAM**.
- Usare i 512 anche per l'HUD significherebbe: startup più lento (la
  decodifica PNG è già oggi il collo con la barra di caricamento), 24 MB di
  VRAM per mostrare 4 riquadri da 88x80 (che di VRAM ne varrebbero ~0.1 MB),
  e qualità PEGGIORE: il downscale 512→88 a runtime (`ResizeImage`,
  filtro povero) sgrana più di un resize offline fatto bene.
- 24 `NN_tiny.png` 88x80 ≈ **0.3 MB totali**: caricamento istantaneo,
  niente barra di attesa per l'HUD.

### 6.3 Proposta

1. **Nuovo script `gen_thumbs.py`** (una tantum, come gli altri `gen_*.py`):
   `portraits/NN_sm.png` → `portraits/NN_tiny.png` a **88x80** con
   PIL/Lanczos + crop centrale — l'arte rifatta è quadrata (1:1), il box è
   32:29 (≈1.10): il **crop** conserva le proporzioni del volto, mentre lo
   squash lo deformerebbe.
2. **dm3d**: l'HUD carica SOLO i tiny (24 file, istantaneo); i 512 restano
   per i quadri sul muro. Il master 1024 non viene mai toccato dal viewer.
3. **Contenuto del mini — DECISO (2026-07-03)**: per ora i **ritratti
   ORIGINALI di Dungeon Master** (32x29 dall'atlante 26, già esportato in
   `portraits/atlas26.raw`), decodificati a 88x80 NEAREST in
   `portraits/hud_NN.png` (estensione di `portraits_decode.py` con scala
   parametrica). Il thumbnail dell'arte rifatta (`gen_thumbs.py`) resta
   descritto qui come variante: quando ci sarà la **pagina di
   configurazione** si sceglierà da lì quale usare.
4. **Ottimizzazione collegata** (nota, non urgente): dm3d oggi carica tutti
   e 24 i `_sm` all'avvio; basterebbe caricare solo gli ordinali presenti
   nella risposta `PORTRAITS` del livello — nei livelli senza specchi lo
   startup diventa immediato.

Convenzione nomi risultante: `NN.png` (1024, master) / `NN_sm.png` (512,
texture muro) / `NN_tiny.png` (88x80, HUD). Regola generale da qui in poi:
**ogni asset raster va tagliato alla dimensione d'uso, il viewer non
ridimensiona mai a runtime**.

---

## 6. File chiave (riferimento rapido)

- `Character.cpp:1230-1503` — `DrawCharacterState` (box campione completo)
- `Character.cpp:911-1000` — `DrawLifeBars` (altezze barre, ceil su 25)
- `Character.cpp:4657` — `CharacterPortraitToStatusBox` (mini-ritratto)
- `Character.cpp:4830-4900` — `DrawAttackButton` (mano/arma, 19x34 @ 233+22i)
- `Character.cpp:4997-5150` — `DrawSpellControls` (barra magie)
- `Character.cpp:1460-1477` — marching order (wRectPos1454, Byte1386, g28)
- `Mouse.cpp:1574-1680` — codici coda 12-15 / 100 / 111 / 125-128 / 70 / 71
- `Data.h:1417,1425` — `wRectPos1454[4]`, `Byte1386[4]`
- `ANALISI_SCHEDA_CAMPIONI.md` — pipeline export/iniezione già rodata
