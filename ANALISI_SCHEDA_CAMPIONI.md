# Analisi — Scheda campione autentica nel viewer 3D

> Obiettivo: al click sul ritratto nel 3D deve comparire la **vera schermata
> dello specchio** di Dungeon Master — manichino con slot equipaggiamento,
> oggetti indossati/in mano, griglia zaino, HEALTH/STAMINA/MANA attuale/massimo,
> LOAD in kg, e i tre bottoni RESURRECT / RE-INCARNATE / CANCEL con le icone —
> non la semplice scheda anagrafica implementata finora (che mostra solo
> nome/titolo/statistiche dal comando `CHAMP`).
>
> Data analisi: 2026-07-02. **Revisione 2026-07-03**: ogni riferimento
> riverificato riga per riga sul sorgente; corretta la parte sull'invocazione di
> ResurrectReincarnateCancel (sistema RESTARTABLE/stateStack, §1.5-bis e §2),
> ridimensionato il "punto critico REINCARNATE" (§2.1), aggiunti i segnaposto
> degli slot vuoti, il routing reale dei click (codici coda mouse), le guardie
> mancanti per MIRROR e i dettagli di formato di barre/LOAD.
> Solo analisi: nessuna modifica al codice.

---

## 1. Come funziona davvero nel 2D (flusso verificato nel sorgente)

Il punto centrale, emerso dall'analisi: **quella schermata non è un'anteprima,
è l'inventario di un campione GIÀ aggiunto (provvisoriamente) al party.**

Catena completa del click sullo specchio:

1. `Mouse.cpp:513-521` — click sull'attuatore murale type 127 →
   `AddCharacter(actuatorValue)`. Qui le guardie sono solo due: ritratto
   attivo (`PORTRAIT_InActive` non settato) e `characterAdded` (un solo
   campione per click). **Le altre guardie stanno DENTRO `AddCharacter`**
   (`Character.cpp:478-479`): `d.EmptyHanded == 0 → return` (mano occupata) e
   `d.NumCharacter == 4 → return` (party pieno); inoltre `pcA3->HP() == 0 →
   return` (`Character.cpp:704`, eroe morto nel record). Buona notizia per il
   bridge: chiamando `AddCharacter` queste tre vengono gratis.
2. `AddCharacter` (`Character.cpp:456-799`):
   - decodifica la scheda dal record dbTEXT (nome, titolo, statistiche — la
     parte già replicata dal nostro comando `CHAMP`);
   - **aggiunge il campione al party**: `d.PotentialCharacterOrdinal =
     slot+1`, `d.NumCharacter++` (`Character.cpp:705-706`) — il "provvisorio"
     è tutto qui: finché `PotentialCharacterOrdinal != 0` il campione non è
     confermato;
   - **equipaggia gli oggetti** che giacciono sulla cella-muro dello specchio
     nei rispettivi slot (`Character.cpp:717-796`). La cella è calcolata **al
     momento della chiamata** da posizione+facing correnti del party
     (`partyX + DeltaX[facing]`, lato muro rivolto al party) → il comando
     `MIRROR` va accettato solo mentre il party è davanti allo specchio;
   - apre la schermata: `ShowHideInventory(slot)` (`Character.cpp:797`), poi
     `TAG02076e()` (refresh viewport).
3. `ShowHideInventory` (`CSBCode.cpp:7470-7575`):
   - guardie/semantica: niente inventario per un campione **morto** (7486-7488),
     return se `PressingMouth`/`PressingEye` (occhio o bocca premuti,
     7490-7491); **toggle**: se l'inventario è già aperto per quel campione la
     chiamata lo chiude (7494-7498); chiamata con `chIdx==4` = "chiudi";
   - alla chiusura: `RepackChest()`, `videoMode = VM_ADVENTURE`, ridisegno;
   - all'apertura: `d.SelectedCharacterOrdinal = chIdx+1` e `videoMode =
     VM_INVENTORY` (7536-7538) — lo stato "inventario aperto per il campione
     N"; disegna il **pannello/manichino** (grafica **17**, riga 7544), se c'è
     un provvisorio cancella l'area cibo/acqua (`FillRectangle` su
     `d.Byte1094`, 7545-7548), le label HEALTH/STAMINA/MANA (7549-7551),
     tutti gli oggetti negli slot (`DisplayBackpackItem` 0..29, 7553-7556),
     lo stato del personaggio (`DrawCharacterState`, 7565: barre,
     occhio/bocca, LOAD);
   - imposta `d.SecondaryButtonList = d.Buttons17760` (7569) — i 38 bottoni
     dell'inventario (mani + slot + occhio/bocca ecc.) — e disabilita la
     tastiera (`d.pKeyXlate1 = NULL`).
4. Il menu Resurrect si sovrappone perché `PotentialCharacterOrdinal != 0`:
   `TAG0189d4` (`Character.cpp:3862-3910`, chiamato da
   `DrawCharacterState:1496` e da `CSBCode.cpp:7444/7457`) vede il provvisorio
   e chiama `TAG0189a8()` (`Character.cpp:3849-3857`) che blitta la grafica
   **40** (il pannello unico con le tre icone RESURRECT/RE-INCARNATE/CANCEL)
   e — dettaglio importante — **imposta `d.DisplayResurrectChestOrScroll = 5`**:
   è questo stato che instrada i click verso i bottoni resurrect (vedi punto
   5). Senza provvisorio, la stessa area mostra invece cibo/acqua o il
   contenuto di chest/pergamena nella mano-arma (`DisplayResurrectChestOrScroll`
   = 0/2/4).
5. Click sui bottoni — la catena reale è più lunga di un hit-test diretto:
   - `OnMouseClick` (`CSBCode.cpp:7658-7748`) fa l'hit-test su
     `PrimaryButtonList`/`SecondaryButtonList` (`SearchButtonList`,
     `CSBCode.cpp:7597`) e accoda in `pMouseQueue` il **codice già risolto**
     del bottone più le coordinate grezze;
   - l'area del pannello inventario risolve al codice **81**;
   - `_HandleMouseEvents` (`Mouse.cpp:1349`) smista: `case 81` →
     `TAG01a6ea(_11_, clickX, clickY)` (`Mouse.cpp:1671-1675`);
   - `_TAG01a6ea` (`CSBCode.cpp:8551-8588`): **solo se
     `d.DisplayResurrectChestOrScroll == 5` E `d.EmptyHanded`** (guardia non
     documentata prima: coi bottoni resurrect a schermo ma un oggetto in mano
     il click è ignorato) ri-hit-testa le aree in `d.Buttons18372`
     (`Data.h:714`, 4 entry: 3 bottoni + terminatore; struct `btn` in
     `CSB.h:1662` = codice, xMin/xMax/yMin/yMax, maschera tasto) → codici
     **160=RESURRECT, 161=REINCARNATE, 162=CANCEL** →
     `ResurrectReincarnateCancel(_1_, D5W)`;
   - `_ResurrectReincarnateCancel` (`CSBCode.cpp:6667-6871`):
     - **CANCEL (162)**: chiude l'inventario (`ShowHideInventory(4)`), POI
       `PotentialCharacterOrdinal=0`, `d.NumCharacter--` (l'ordine conta:
       la chiusura legge ancora il provvisorio); se era l'unico campione
       `SetHandOwner(-1)`; gli oggetti restano sulla cella (6683-6710);
     - **RESURRECT (160)**: stacca fisicamente le 30 possession dalla cella
       (`RemoveObjectFromRoom`, 6723-6730), **disabilita gli
       attuatori-ritratto** (due passate: prima il primo attivo con
       `PortraitTranslateDisable`, poi tutti i type-127 della cella salvo
       `BlockDisables`; type 127→0, 6740-6786), conferma il campione, stampa
       "NOME RESURRECTED" nello scroller di testo;
     - **REINCARNATE (161)**: stesso percorso (possession staccate, attuatori
       disabilitati), poi chiama `ReIncarnate(_2_, pcA3)`
       (`Code13ea4.cpp:20`) — la UI di inserimento nome/titolo (griglia
       lettere, grafica **27**) — poi azzera le skill (`ClearMemory` su
       `skills92`) e, **solo con `!DM_rules`**, applica le penalità
       `reincarnateAttributePenalty`/`reincarnateStatPenalty` ad
       attributi/HP/Stamina/Mana; in chiusura 12 incrementi +1 su attributi
       casuali (6789-6835).

### 1.5-bis. Il sistema RESTARTABLE (correzione importante)

`_ResurrectReincarnateCancel`, `_TAG01a6ea` e `_ReIncarnate` **non sono
funzioni normali**: sono `RESTARTABLE`, coroutine cooperative del motore.
`ResurrectReincarnateCancel(n, code)` è una macro `CALL1` (`CSB.h:3671`,
implementazione `Dispatch.h:281`) che:

1. salva lo stato di ripresa del chiamante (`mystate = n`);
2. chiama `_CALL1(st_ResurrectReincarnateCancel, code)` (`system.cpp:747`)
   che **pusha lo stato su `stateStack`** (max 20 frame, `system.cpp:700-702`)
   e posta il messaggio `UIM_INITIALFUNCTIONENTRY`;
3. ritorna subito. L'esecuzione vera avviene quando il message pump di
   `CSBUI` processa il messaggio: `DispatchCSB` (`system.cpp:1010`) esegue la
   cima dello stack (`case st_ResurrectReincarnateCancel`, `system.cpp:1123`).
   `DispatchCSB` è protetto dalla macro `EC` (`stdafx.h:61`).

Conseguenze per il bridge:

- **NON chiamare `_ResurrectReincarnateCancel(160)` direttamente** da
  `Bridge_Tick`: la `RETURN` finale fa `_RETURN()` → `numState--`
  (`system.cpp:934`) su un frame mai pushato → corruzione dello stateStack
  (che alla base tiene `_MainLoop`).
- La macro `CALL1` non è usabile fuori da una RESTARTABLE (usa `mystate` e
  label generate). L'equivalente legale da codice normale è
  `_CALL1(st_ResurrectReincarnateCancel, code)` — ma bypassa le guardie di
  `_TAG01a6ea` (vedi §2).
- `AddCharacter` invece **è una funzione normale** (`void AddCharacter(i16)`),
  e tutte le funzioni che chiama nel percorso mirror (`ShowHideInventory`,
  `DisplayBackpackItem`, `DrawCharacterState`…) sono normali: chiamarla da
  `Bridge_Tick` è lecito.

### Perché il pannello attuale "è un'altra cosa"
Il comando `CHAMP` legge la scheda dbTEXT **senza toccare lo stato**: non
esiste nessun campione, quindi niente oggetti equipaggiati, niente LOAD,
niente barre attuale/massimo, e i bottoni non avrebbero nulla di reale da
comandare. La schermata vera richiede che il provvisorio esista nel motore.

---

## 2. Le due strade possibili

### Strada A — il 2D resta il cervello (CONSIGLIATA)
Il click sul ritratto nel 3D **esegue davvero** ciò che fa il click nel 2D:

- nuovo comando bridge `MIRROR <ord>` → chiama `AddCharacter(ord)`
  direttamente da `Bridge_Tick` (funzione normale, lecito; coperta dalla
  blindatura già presente in `Bridge_Tick`). Lo stato di gioco cambia sul
  serio: il campione provvisorio entra nel party e **anche il 2D passa alla
  schermata inventario** (coerenza totale fra i due schermi, che è la
  filosofia del ponte). **Guardie a carico del bridge** (il 2D le garantisce
  implicitamente col contesto, `AddCharacter` NON le controlla):
  - `d.PotentialCharacterOrdinal == 0` (nessun altro provvisorio pendente);
  - `d.SelectedCharacterOrdinal == 0` (inventario chiuso — nel 2D con
    l'inventario aperto lo specchio non è cliccabile perché il viewport
    mostra l'inventario);
  - davanti al party c'è davvero un attuatore type-127 **attivo** con
    `value == ord` (il codice del comando `CHAMP` già sa trovarlo);
  mano vuota, party pieno e campione morto sono già dentro `AddCharacter`.
- il bridge espone nel **push** tre campi nuovi: `PotentialCharacterOrdinal`,
  `SelectedCharacterOrdinal` e `DisplayResurrectChestOrScroll` (0=cibo/acqua,
  2=pergamena, 4=chest, 5=menu resurrect — dice al 3D COSA mostrare
  nell'area sotto il manichino). Così il 3D sa quando aprire/chiudere il
  pannello — e la sincronizzazione chiesta ("se clicco nel 2D compare anche
  nel 3D?") viene **gratis, nei due sensi**;
- nuovo comando `SHEET` → serializza la scheda completa del campione
  selezionato dal CHARDESC vivo: nome/titolo, HP/Stamina/Mana attuale+massimo,
  LOAD attuale + massimo (`MaxLoad`, `Code11f52.cpp:4318-4350`, in decimi di
  kg), e le **30 possession** (slot → objectNameIndex → atlante/cella icona);
- bottoni del pannello 3D → comando `RRC <160|161|162>` implementato
  **iniettando il click nella coda mouse** — l'estensione naturale di
  `Bridge_InjectCommand` (`Bridge.cpp:936-945`), che già inietta i codici
  movimento 1-6: si accoda `{num: 81, x, y}` con le coordinate del **centro
  del bottone** ricavate a runtime da `d.Buttons18372` (rect esportati una
  volta insieme alle grafiche). Il click percorre l'identico cammino del 2D
  (`_HandleMouseEvents` → `_TAG01a6ea` → RRC): guardie
  (`DisplayResurrectChestOrScroll==5`, `EmptyHanded`), contesto RESTARTABLE
  e timing tutti corretti, zero logica duplicata e zero rischi di stack.
  Alternativa (sconsigliata): `_CALL1(st_ResurrectReincarnateCancel, code)`
  — funziona ma bypassa le guardie, che andrebbero replicate a mano.

#### 2.1 REINCARNATE — punto critico ridimensionato

`_ReIncarnate` **non blocca il processo**: il suo loop usa
`WAITFORMESSAGE(_2_)` (`Code13ea4.cpp:105`), che cede al message loop e viene
rientrato a ogni messaggio. Durante il name-entry:

- il main loop SDL continua a girare: `Process_ecode_IDC_Timer`
  (`CSBlinux.cpp:579-599`) continua a chiamare `CSBUI(UIM_TIMER)` e poi
  **`Bridge_Tick()` — il bridge resta vivo** (push e comandi funzionano);
- la logica di gioco però è sospesa: la cima dello stateStack è
  `_ReIncarnate`, ogni messaggio rientra lì e non in `_MainLoop`. Il bridge
  **non deve iniettare altri comandi** (movimento, MIRROR) finché il
  name-entry è attivo — sentinella: `keyboardMode == 2`;
- i click in questa modalità vengono accodati da `OnMouseClick` come
  `{num: 0xade, x, y}` **con coordinate grezze** (`CSBCode.cpp:7683-7685`) e
  `_ReIncarnate` consuma direttamente la coda mouse (griglia lettere
  x107-215 y116-144, backspace x107-175 y147-155, OK x197-215 y147-155,
  hardcoded in `Code13ea4.cpp:128-186`). Quindi **il name-entry dal 3D è a
  sua volta pura iniezione di click** — stessa meccanica di RRC, nessuna UI
  bloccante da aggirare. Resta in fase 3 solo perché serve replicare la
  griglia lettere (grafica 27) nel pannello 3D.

Per la prima fase: supportare **RESURRECT e CANCEL** dal 3D; se l'utente
preme REINCARNATE (dal 2D o dal 3D), il 3D mostra "inserimento nome in corso
nel 2D" finché `keyboardMode` torna 1.

### Strada B — replica in sola lettura (SCARTATA)
Ricostruire la schermata senza toccare lo stato: parse read-only delle
possession dalla cella (rifacendo la logica slot di `Character.cpp:732-791`),
LOAD calcolato a mano, bottoni finti. Fedele solo nell'aspetto: doppia
implementazione della logica di equipaggiamento da tenere allineata, e al
momento di premere RESURRECT bisognerebbe comunque mutare lo stato. Tutto il
costo, metà del risultato.

---

## 3. Grafiche da esportare (pipeline già pronta: dump dal bridge + `gfx_decode.py`)

| Grafica | Contenuto | Riferimento |
|---|---|---|
| **17** | pannello/manichino inventario (sfondo schermata) | `CSBCode.cpp:7544` |
| **40** | pannello bottoni RESURRECT/RE-INCARNATE/CANCEL (icone incluse) | `Character.cpp:3853` |
| **42..48** | 7 atlanti **icone oggetti**, celle 16×16, 16 colonne; atlante = maggiore `i` con `Word612[i] <= objectNameIndex`, cella = `objectNameIndex - Word612[i]` | `DrawItem`, `Character.cpp:32-77` |
| **33 / 34 / 35** | cornici slot 18×18: verde normale / rossa "ouch" (parte del corpo colpita) / blu "mano che attacca" — 33/34 riusate anche per i **box occhio e bocca** (34 = rosso: bocca se fame/sete/veleno, occhio se un attributo sotto il massimo) | `Character.cpp:1163-1205, 1365-1394` |
| **37 / 38 / 39** | icone shield attorno al box del personaggio: 37=party/personale, 38=fire, 39=spell | `Character.cpp:1266-1287` |
| **28** | icona posizione/direzione campione, celle **19px**, colonna = `(facing+4-partyFacing)&3`, su sfondo riempito col colore campione `d.Byte1386[chIdx]` | `Character.cpp:1460-1477` |
| **8** | box campione morto | `Character.cpp:1303` |
| **27** | griglia lettere del name-entry (per REINCARNATE, fase 3) | `Code13ea4.cpp:46` |
| 26 | atlante ritratti 32×29, colonna `charID&7`, riga `charID>>3` — **già esportato** (`portraits/`) | `Character.cpp:490` |

- **Slot vuoti = sagome segnaposto** (dettaglio indispensabile per la replica
  fedele): quando `Possession(n) == RNnul`, `DisplayBackpackItem` disegna
  comunque un'icona dagli **stessi atlanti oggetti**: slot 0-5 →
  `objNI_Special_n + 2*slot` (variante +1 se "ouch"), slot 10-13 →
  `objNI_Special_j + (slot-10)`, altri → `objNI_Special_f` generico
  (`Character.cpp:1152-1176`). Sono le sagome elmo/stivali/collana ecc. sul
  manichino.
- **Font**: correzione — È caricato dalla stessa pipeline grafica:
  `ReadAndExpandGraphic(0x822d, d.Pointer12926, 0, 0, 768)`
  (`CSBCode.cpp:1804-1805`), 768 byte, avanzamento 6px per carattere
  (`BltOneChar`, `Graphics.cpp:339-347`). Esportabile col dump usando l'ID
  `0x822d`, oppure il 3D usa il proprio font (scelta estetica).
- **Geometria degli slot**: le posizioni pixel del manichino sono dati, non
  codice: `d.IconDisplay[46]` (`CSB.h:652` per la struct — triple
  pixelX/pixelY/objectType; `Data.h:1455`), composizione: **0-7** le 8 mani
  nei box ritratto in alto (2 per campione), **8-37** i 30 slot inventario
  (slot `n` → `IconDisplay[n+8]`, `DisplayBackpackItem`,
  `Character.cpp:1123`), **38-45** gli 8 slot del contenuto chest
  (`d.rnChestContents`, `Character.cpp:1128-1131`). Il bridge le esporta una
  volta (46 triple) e il 3D replica il layout esatto senza hardcodare nulla.
- **Rect dei bottoni resurrect**: esportare anche le 3 entry di
  `d.Buttons18372` (codice + rettangolo) — servono sia per disegnare le aree
  cliccabili nel 3D sia per calcolare le coordinate da iniettare col
  comando `RRC` (§2).

## 4. Mapping slot possession (per disegnare gli oggetti al posto giusto)

Da `Character.cpp:732-791` (equip in `AddCharacter`) + commento di
`HandleClothingClick` (`CSBCode.cpp:6969-6975`) + usi confermati nel motore:

| Slot | Significato |
|---|---|
| 0 | mano pronta / scudo |
| 1 | mano arma/azione (la scimitarra dello screenshot) |
| 2 / 3 / 4 / 5 | testa / torso / gambe / piedi |
| 6, 11 | borse (scroll/pozioni; l'equip preferisce 11, poi 6) |
| 10 | collo (collana) |
| 7, 8, 9, 12 | faretra e borse aggiuntive (bit 0x40/0x80/0x100 di `word4`) |
| 13..29 | zaino (17 slot generici) |
| 30..37 | contenuto della chest aperta (`d.rnChestContents`, non del campione) |

Maschere carry-location: commentate su `OBJDESC::word4` (`CSB.h:1680-1690`:
0x01 bocca, 0x02 testa, 0x04 collo, 0x08 torso, 0x10 gambe, 0x20 piedi,
0x40/0x80 faretra ready/not-ready, 0x100 borsa, 0x400 chest); tabella
`d.CarryLocation[38]` (`Data.h:1436`).

Catena click sugli slot nel 2D (utile in futuro per spostare oggetti dal 3D —
anch'essa pura iniezione): bottoni `Buttons17760` → codici coda **28-65** →
`HandleClothingClick(code-20)` (`Mouse.cpp:1599-1607`, `CSBCode.cpp:6979`)
→ slot = arg-8.

### 4.1 Formato dei valori a schermo (per il comando `SHEET`)

- Barre e numeri: `PrintLifeForces` (`Character.cpp:1088-1094`) mostra
  `HP/MaxHP` e `Mana/MaxMana` così come sono, ma **STAMINA divisa per 10**.
  `SHEET` esporti i valori grezzi; la /10 è convenzione di display.
- LOAD: `pcA3->load` in decimi di kg, stringa `"x.y/max KG"` con max
  visualizzato `(MaxLoad+5)/10` (`Character.cpp:1419-1455`). Colore: rosso
  (`COLOR_8`) se `load > MaxLoad`, giallo (`COLOR_11`) se `8*load > 5*MaxLoad`
  (cioè oltre il 62,5%), altrimenti normale (`COLOR_13`).
- `MaxLoad` (`Code11f52.cpp:4318-4350`): `8*Forza + 100`, corretto per
  stamina, penalità "ouch", bonus Elven Boots (+1/16), arrotondato per
  difetto al multiplo di 10.
- Occhio/bocca: codici coda 71/70 (`ClickOnEye`/`FeedCharacter`,
  `Mouse.cpp:1655-1665`) — fuori scope fase 2, ma anch'essi iniettabili.

---

## 5. Piano proposto — **IMPLEMENTATO il 2026-07-03** (Fase 1 + Fase 2)

> Stato: Fase 1 (bridge: export `invgfx/`, push esteso, comandi
> `MIRROR`/`SHEET`/`RRC`, `CMD` esteso ai codici 7-10) e Fase 2 (pannello
> autentico in dm3d a scala 2.75, X di chiusura, pad movimento nascosto a
> pannello aperto, auto-CANCEL se il party si muove/gira) sono FUNZIONANTI e
> verificate end-to-end. RRC via iniezione codice-81 come da §2. Gotcha
> scoperto: `DrawImageRect` dell'engine campiona il rettangolo sorgente
> dall'immagine SPECCHIATA verticalmente e il blit è una copia raw senza
> alpha — risolto con atlanti pre-specchiati `gNf.png` e colorkey applicati
> in `gfx_decode.py` (vedi commenti lì e `test_atlas.bb`). Resta la Fase 3
> (REINCARNATE/name-entry dal 3D) e l'HUD (box campioni, marching order,
> mano, barra magie).

1. **Fase 1 — bridge** (~150-200 righe):
   - export one-shot delle grafiche 17, 40, 42-48, 33-35, 37-39, 28, 8 (+27,
     + font 0x822d), delle 46 posizioni `IconDisplay` e dei 3 rect di
     `Buttons18372` → decodifica con `gfx_decode.py`;
   - push esteso con `PotentialCharacterOrdinal`, `SelectedCharacterOrdinal`
     e `DisplayResurrectChestOrScroll`;
   - comandi `MIRROR <ord>` (chiamata diretta ad `AddCharacter` + guardie
     §2), `SHEET` (valori grezzi §4.1), `RRC <code>` (iniezione
     `{num:81, x, y}` sul centro-bottone, riuso di `Bridge_InjectCommand`).
2. **Fase 2 — dm3d** (~200-250 righe):
   - pannello con le grafiche vere: manichino (17), oggetti negli slot via
     `IconDisplay` **incluse le sagome degli slot vuoti** (§3), cornici
     33/34/35, barre (STAMINA/10) e LOAD col colore giusto, pannello bottoni
     (40);
   - click sul ritratto 3D → `MIRROR`; apertura/chiusura pilotata dal push;
     bottoni RESURRECT/CANCEL → `RRC 160/162`; durante `keyboardMode==2`
     (name-entry nel 2D) il pannello 3D mostra un avviso e il bridge
     sospende l'iniezione comandi;
   - il pannello anagrafico attuale (`CHAMP`) viene sostituito (o declassato
     a tooltip rapido).
3. **Fase 3 — REINCARNATE** dal 3D: replica della griglia lettere (grafica
   27) + iniezione di click `{num:0xade, x, y}` sulle aree hardcoded di
   `Code13ea4.cpp:128-186`. Meccanicamente identica a RRC, solo più UI.

Fuori scope (per ora), ma tutti raggiungibili con la stessa iniezione in coda
mouse senza nuovo codice motore:
- aprire l'inventario dei campioni già confermati: codici coda **7-10**
  (`ShowHideInventory(D7W)`, guardia `PotentialCharacterOrdinal==0`,
  `Mouse.cpp:1610-1623`);
- spostare oggetti fra gli slot dal 3D: codici **28-65**
  (`HandleClothingClick`, §4);
- occhio/bocca: codici **71/70**.

---

## 6. File chiave del motore (riferimento rapido)

- `Character.cpp:456-799` — `AddCharacter` (provvisorio + equip + apertura)
- `CSBCode.cpp:7470-7575` — `ShowHideInventory` (setup schermata, toggle)
- `Character.cpp:1230-1503` — `DrawCharacterState` (barre, occhio/bocca, LOAD)
- `Character.cpp:1100-1228` + `:32-77` — `DisplayBackpackItem` / `DrawItem`
  (incl. segnaposto slot vuoti)
- `Character.cpp:3849-3910` — `TAG0189a8`/`TAG0189d4` (menu Resurrect,
  grafica 40, `DisplayResurrectChestOrScroll=5`)
- `CSBCode.cpp:6667-6871` — `_ResurrectReincarnateCancel` (160/161/162)
- `CSBCode.cpp:8551-8588` — `_TAG01a6ea` (hit-test `Buttons18372`, guardie)
- `CSBCode.cpp:7658-7748` — `OnMouseClick` (hit-test → codice in coda;
  `keyboardMode==2` → `0xade` con xy grezze)
- `CSBCode.cpp:7597` — `SearchButtonList`
- `Mouse.cpp:1349-1720` — `_HandleMouseEvents` (smistamento codici coda:
  1-6 movimento, 7-10/83 inventario, 28-65 slot, 70/71 bocca/occhio,
  80 viewport, **81 pannello inventario**, 145/146 sleep/wake)
- `system.cpp:700-1010` — stateStack: `_CALL1` (747), `_RETURN` (934),
  `DispatchCSB` (1010; `st_ResurrectReincarnateCancel` a 1123)
- `Code13ea4.cpp:20-328` — `_ReIncarnate` (name-entry via WAITFORMESSAGE,
  aree click 128-186)
- `Code11f52.cpp:4318-4350` — `MaxLoad`
- `Bridge.cpp:936-945` — `Bridge_InjectCommand` (iniezione coda mouse, da
  estendere per RRC)
- `CSBCode.cpp:1804-1805` — caricamento font (`0x822d`, 768 byte)
