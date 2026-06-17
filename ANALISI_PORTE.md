# Analisi — Porte (serrature, pulsanti, pressure pad)

> Obiettivo: far funzionare le porte "come si deve" nel visualizzatore 3D
> (`dm3d.bb`). Alcune porte hanno la serratura, altre si aprono con un pulsante.
> Questo documento è **analisi/progetto**, non implementazione.
>
> Data: 2026-06-17 · Sorgente motore: `csb/CSBwin_SRC_20190702/src`.

---

## 1. Idea di fondo

Il motore **CSBwin simula già tutto**: serrature, chiavi, pulsanti, leve,
pressure pad, collegamenti pulsante→porta, timer, mostri che bloccano l'anta.
Il punto non è *reimplementare* questa logica nel viewer — sarebbe enorme e
fragile — ma **esporla tramite il ponte e renderizzarla**. Coerente con la
filosofia del progetto: **cervello (CSBwin) → ponte → corpo (renderer Blitz3D)**.

> "Serratura" vs "pulsante" **non è una proprietà della porta**: è una porta
> *chiusa* + un attuatore vicino che la prende di mira. Keyhole = attuatore che
> richiede una **chiave** (item) per scattare; pulsante/leva = attuatore che
> scatta alla pressione; pressure pad = attuatore a pavimento che scatta al
> passaggio.

---

## 2. Cosa sa già il motore (modello dati verificato)

### 2.1 Stato della porta — nei flag della cella
- `doorState = GetCellFlags(x,y) & 7`
  (`Monster.cpp:310`, `Code11f52.cpp:1884`).
  Valori: ~0 = aperta … 3 = quasi chiusa, 4 = chiusa, 5 = speciale
  (foro / distrutta). È **dinamico**: cambia da solo quando la porta si
  apre/chiude. Il ponte già usa `GetCellFlags` (per il room type `>>5`),
  quindi leggere `&7` è immediato.
- `DB0::doorType()` 0/1 — due stili di porta per livello;
  `d.DoorTOC[doorType]` contiene i flag della porta (es. `0x100` = lascia
  passare i proiettili).

### 2.2 I dispositivi — sono ATTUATORI (`dbACTUATOR` / `DB3`)
Stessa famiglia dei ritratti dei campioni (che erano `actuatorType()==127`).
Ogni attuatore (`CSB.h:930`) espone già:

| Accessore | Significato |
|---|---|
| `wallGraphicOrdinal()` (`CSB.h:1043`) | aspetto sul muro (serratura / pulsante / leva) |
| `targetX()` / `targetY()` (`CSB.h:1029-1030`) | **quale cella-porta** comanda (bit `word4&0x800` = nessun target) |
| `action()` (`CSB.h:1034`, enum `CSB.h:905-910`) | SET / CLEAR / TOGGLE / CONSTPRESSURE |
| `doorSwitch()` bit (`CSB.h:846`) | è un interruttore-porta |
| `actuatorType()` | la classe (1 = wall switch semplice, … keyhole, ecc.) |
| `pos()` | lato del muro su cui è montato (come per i ritratti) |

Riferimenti utili: `DrawDoorSwitch` (`CSB.h:3232`), `IsAlcove` (`CSB.h:3311`),
pressure pad `soundPRESSUREPAD` (`CSB.h:626`),
azioni mouse/switch `OnMouseSwitchAction` (`CSB.h:3196`),
`QueueSwitchAction` (`CSB.h:3651`).

---

## 3. Piano consigliato (a fasi)

### Fase A — Porte che si aprono/chiudono davvero (sola lettura). *Piccola, alto impatto.*
- **Ponte**: nello stato per-tick, per ogni porta vicina esporta `doorState`
  (`&7`) + `doorType`.
- **Viewer**: sostituisce il cubo-marker marrone con una vera anta nel telaio,
  con altezza impostata dallo `doorState` (4 = giù/chiusa … 0 = su/aperta),
  **interpolando tra i tick** per uno scorrimento fluido. Texture dell'anta
  secondo `doorType`.
- **Risultato**: apri una porta nella finestra CSBwin (con chiave o pulsante) e
  nel 3D scorre. Zero logica di gioco nel viewer.

### Fase A+ — Mostrare i dispositivi sul muro (ancora sola lettura). *Riusa il codice dei ritratti.*
- **Ponte**: estende la scansione attuatori già scritta per i ritratti,
  esportando gli "interagibili": cella + lato (`pos()`) + `wallGraphicOrdinal`
  + `targetX/Y` + classe (serratura / pulsante / leva / pressure-pad).
- **Viewer**: disegna il widget giusto sul muro (placca con serratura, pulsante
  tondo, leva). Opzionale: un marker tenue sulla porta bersaglio, così si vede
  "questo pulsante apre quella porta".

### Fase B — Interagire dal 3D (opzionale, più grande).
- Canale inverso **viewer → motore**: picking del mouse (ray → cella + lato),
  scrittura di un comando ("usa attuatore a X,Y,lato" / "usa item su serratura")
  in un file che il ponte legge, chiamando le funzioni già esistenti del motore
  (`OnMouseSwitchAction` / `PerformLocalAction` / `QueueSwitchAction`).
- Richiede: picking + protocollo file-comando + gestione item selezionato per le
  serrature + un hook nel motore. Finché non c'è, si interagisce nella finestra
  CSBwin e il 3D rispecchia.

---

## 4. Distinzione serratura/pulsante (per la grafica, non per la logica)

Non serve al funzionamento (ci pensa il motore). Per la resa, si classifica
l'attuatore dal suo `wallGraphicOrdinal` / `actuatorType`:
- grafica-serratura → "serve una chiave";
- pulsante / leva → "premi";
- pad a pavimento → "calpesta".

---

## 5. Ordine suggerito

Partire dalla **Fase A** (porte animate via `doorState`): è il blocco che dà
subito il risultato "porte come si deve", a basso rischio, poggiando su dati che
il motore già aggiorna. Poi **A+** per i widget sul muro; infine **B** se si
vuole giocare interamente dal 3D.
