; dm3d.bb - Visualizzatore 3D di Dungeon Master (Fase 1, prototipo).
;
; CLIENT TCP del "ponte": si collega al server del bridge dentro CSBwin
; (porta 5417) e da li' riceve la MAPPA (MAP, una volta), i dati statici
; (STATE per i tipi porta, PORTRAITS, TEXTS) e lo stato live in PUSH (SUB).
; I comandi di movimento tornano indietro sulla stessa connessione (CMD <n>).
; Niente piu' polling dei file: il protocollo e' a righe, framed "OK/PUSH <n>".
;
; Si puo' avviare anche PRIMA del gioco: aspetta la connessione e il dungeon.
; ESC per uscire.

; Finestra: barra campioni in alto (HUD, tutta larghezza) + 3D a sinistra
; (640x480) + barra nera azioni a destra (200px). Vedi ANALISI_HUD_3D.md §1.
Const VIEW_W = 640         ; larghezza viewport 3D
Const BAR_W  = 200         ; larghezza barra azioni (un po' piu' larga dei tasti)
Const BARH   = 88          ; altezza barra campioni (box 2D 29px * 2.75 + margine)
Const WINH   = 480 + BARH  ; altezza finestra totale
Graphics3D VIEW_W+BAR_W, WINH, 0, 2
SetBuffer BackBuffer()

Const CELL# = 4.0          ; unita' 3D per cella della griglia
Const EYE#  = 2.0          ; altezza occhi camera
Const SMOOTH# = 0.15       ; interpolazione camera (piu basso = piu fluido tra gli step)

Const NPORT = 24           ; ritratti dei campioni: portraits/00.png .. portraits/23.png
Dim portEnt(NPORT-1)       ; entity del quadro sul muro (0 = non piazzato)
Dim portSeen(NPORT-1)      ; visto nell'ultimo PORTS (0 = sparito -> reclutato/HidePortrait)
Global partySize = -1      ; n. slot attivi visto nell'ultimo push (per PORTS)
Global prevPcPot = 0       ; pcPot del push precedente (conferma/cancel -> PORTS)
Const PORT_W# = CELL*0.4125 ; larghezza/altezza del quadro appeso al muro (-25%)
Const PORT_T# = 0.10       ; spessore della tavola (sottile)
Const PORT_USED_ALPHA# = 0.08  ; specchio usato: volto quasi svanito (0=cornice vuota)

; targhe di marmo con le scritte (dm_texts_0.txt + plaque_0_<i>.png da gen_plaques.py)
Const PLAQ_W# = CELL*0.74  ; larghezza targa (lungo il muro)
Const PLAQ_H# = CELL*0.30  ; altezza targa
Const PLAQ_T# = 0.10       ; spessore lastra
Const PLAQ_OUT# = 0.06     ; di quanto la faccia esterna SPORGE dalla parete

Const DOOR_T# = 0.30       ; mezzo-spessore dell'anta (asse "stretto")
Const DOOR_W# = CELL/2.0 - 0.15   ; mezza-larghezza dell'anta (riempie il varco)
Const DOOR_H# = CELL/2.0 - 0.08   ; mezza-altezza dell'anta (pavimento->soffitto)
Const DOOR_SMOOTH# = 0.20  ; interpolazione scorrimento porta (0..1)
Const DOORCAM = 0          ; 1 = camera di debug fissa davanti alla prima porta; 0 = segue il party
Const SHOT = 0             ; 1 = salva uno screenshot (dm3d_shot.bmp) ed esce (per il print screen)

Global woodtex             ; texture legno dell'anta (vista anche da BuildWoodDoor)
Global irontex             ; texture ferro della grata (vista anche da BuildIronDoor)
Global walltex             ; texture muro (vista anche da BuildDoorFrame per gli stipiti)
Global jambtex             ; stessa texture muro ma con UV scalate per gli stipiti stretti
Global mvImg = 0           ; immagine dei pulsanti movimento (mvbuttons.png, estratta da DM)
Global mvImgDark = 0       ; versione SCURITA (pannello scheda aperto: disabilitati)
Global hovImg = 0          ; highlight semi-trasparente per la cella sotto il mouse

; ------------------------------------------ connessione al ponte (server TCP)
; Il bridge dentro CSBwin ascolta sulla porta 5417 (vedi Bridge.cpp). Risposte
; framed: "OK <n>" + n righe (stessi formati dei vecchi .txt), "ERR <msg>" se
; il dungeon non e' ancora caricato; con SUB arrivano push spontanei "PUSH <n>".
Const BRIDGE_HOST$ = "127.0.0.1"
Const BRIDGE_PORT  = 5417
Global bridge = 0                 ; stream TCP verso CSBwin (0 = non connesso)
Global brN                        ; n. righe payload dell'ultima risposta/push
Dim brLine$(8191)                 ; payload dell'ultima risposta/push

Global gClick = 0                 ; MouseHit(1) del frame (condiviso pick/pad)

; ---- schermata inventario/scheda campione (la VERA schermata dello specchio) --
; Click sul ritratto nel 3D -> comando MIRROR (AddCharacter nel motore: il
; campione entra PROVVISORIAMENTE nel party e ANCHE il 2D apre l'inventario).
; Il pannello 3D e' pilotato dal push (pcSel/pcPot) e disegnato con le grafiche
; VERE estratte dal bridge (invgfx/, decodificate a scala 2 da gfx_decode.py).
; Vedi ANALISI_SCHEDA_CAMPIONI.md.
; scala 2.75: il pannello 224x136 diventa 616x374 e riempie quasi tutta la
; larghezza del viewport 3D (640). Con 2.75 la cella degli atlanti resta un
; numero INTERO di pixel (16*2.75 = 44): DrawImageRect non sbava mai sulla
; cella vicina. I PNG vanno decodificati alla stessa scala:
;   python3 gfx_decode.py invgfx 2.75
Const INVSC#  = 2.75              ; scala dei PNG decodificati
Const INVX0   = 12                ; = (VIEW_W - 224*2.75) / 2
Const INVY0   = 53 + BARH         ; = (480 - 136*2.75)/2, sotto la barra HUD

; ---- barra campioni in alto (HUD, sezione CHAMPS del push) ----------------
; 4 box a passo 69*2.75=190 (2D: 69px, box 66x29), stessa scala dell'inventario.
Const HBOXW = 181                 ; = 66*2.75
Const HBOXH = 80                  ; = 29*2.75 (il mini-ritratto e' 88x80)
Const HBOXY = 4                   ; = (BARH - HBOXH) / 2
Dim hcAct(3)                      ; slot attivo (il provvisorio conta)
Dim hcName$(3)
Dim hcPort(3)                     ; ordinale ritratto (-1 = sconosciuto)
Dim hcHP(3), hcMaxHP(3), hcStam(3), hcMaxStam(3), hcMana(3), hcMaxMana(3)
Dim hcPos(3), hcFacing(3), hcCol(3), hcShield(3)
Dim hcH0d(3), hcH0f(3), hcH1d(3), hcH1f(3)   ; mani: icona risolta + cornice
Global hcFire = 0, hcSpell = 0, hcParty = 0  ; scudi di party
Global hcCaster = -1, hcHand = -1, hcHandNI = -1
Global hudValid = 0               ; sezione CHAMPS ricevuta almeno una volta
Dim hudPort(23)                   ; mini-ritratti ORIGINALI DM (portraits/hud_NN.png)
Global invImg8 = 0                ; box campione morto (g8f)
Global invImg37 = 0, invImg38 = 0, invImg39 = 0   ; scudi party/fire/spell
; pannello FOOD/WATER (mostrato al posto del menu resurrect per i campioni
; confermati: DisplayFoodWater, Viewport.cpp:7240)
Global invImg20 = 0               ; box food/water (stesso rect di g40)
Global invImg30 = 0, invImg31 = 0, invImg32 = 0   ; label FOOD/WATER + veleno
; quadrato marching-order (posizioni PG nel party, colonna destra in alto):
; geometria delle 4 celle dai rect wRectPos1454 esportati, colore campione
; dalla palette DM, freccia di facing dalla grafica 28 (celle 19x14)
Dim invArrow(3)                   ; figurine pos/dir (g28c0..3: celle-PNG
                                  ; separate; DrawImage rispetta l'alpha)
; font ORIGINALE di Dungeon Master (dmfont_decode.py): atlanti 16x8 glifi in
; cella 17px caricati con LoadAnimImage -> DrawImage img,x,y,ASCII disegna il
; glifo con alpha. Un atlante per colore (come i colori-testo del 2D).
Global fntGrey = 0                ; testi normali (palette 13)
Global fntOrange = 0              ; possessore del cursore-mano (palette 9)
Global fntYellow = 0              ; LOAD oltre i 5/8 (palette 11)
Global fntRed = 0                 ; LOAD in sovraccarico (palette 8)
Global fntGreen = 0               ; attributo potenziato (palette 7, occhio)
; ---- barra magie (HUD-4, ANALISI_HUD_3D.md §2.4) ---------------------------
; Replica dell'area magia 2D (x 224..319, y 42..73): linguette caster,
; riga delle 6 rune dello stadio corrente (glifi font 96+6*stadio+i),
; incantation in composizione (essa stessa bottone "cast") e backspace.
; Scala 2.0 (96px 2D -> 192px nella colonna destra da 200), quindi font
; dedicati font_*2.png (cella 12) e g9 ridimensionato al load.
Const MAGSC# = 2.0
Global spStage = -1               ; byte33 del caster: rune gia' scelte (0..3)
Dim spInc(3)                      ; incantation: codici glifo 96..119 (0=vuoto)
Global fntCyan2 = 0               ; rune/incantation (palette 4, scala 2)
Global fntBlack2 = 0              ; nome caster sulla linguetta (palette 0)
Global magN = 0                   ; bottoni Buttons18060 (101-106 rune,
Dim magCode(8)                    ;   107 backspace, 108 cast) da layout.txt,
Dim magBX1(8), magBX2(8), magBY1(8), magBY2(8)   ;   rect in spazio mouse 2D
Global mag12X = 224, mag12Y = 42  ; rect Word12 (area pannello, per riferimento)
Global magOX = 0, magOY = 0       ; origine schermo della barra (calcolata)
; ---- pannello attacco (HUD-3, ANALISI_HUD_3D.md §2.3) ----------------------
; Area 2D x 224..319, y 77..120 sotto la barra magie, stessa scala MAGSC e
; origine MagX/MagY: a menu chiuso 4 pannelli arma 20x35 (DrawAttackButton),
; a menu aperto il pannello g10 con nome e opzioni (DrawLegalAttackTypes).
Dim amenuX1(3), amenuX2(3), amenuY1(3), amenuY2(3) ; rect g10 per 1..3 opzioni
Global atkN = 0                   ; Buttons17952: pannelli arma (116-119)
Dim atkCode(4), atkBX1(4), atkBX2(4), atkBY1(4), atkBY2(4)
Global obtN = 0                   ; Buttons17892: menu aperto (112 pass, 113-115)
Dim obtCode(4), obtBX1(4), obtBX2(4), obtBY1(4), obtBY2(4)
Dim hcAtkNI(3)                    ; icona pannello: -2 nero (morto), -1 pieno, NI
Dim hcAtkDis(3)                   ; 1 = ombreggiato (cooldown/provvisorio/sonno)
Global atkPanels = 0              ; Word20252: pannelli visibili
Global atkOrd = 0                 ; AttackingCharacterOrdinal (menu aperto se >0)
Global atkNum = 0                 ; numero opzioni del menu
Dim atkName$(2)                   ; nomi azione dal push ("-" = riga vuota)
Dim atkIcImg(3), atkIcNI(3)       ; cache sagoma arma per slot (LoadImage di
                                  ;   invgfx/icons/sil_<a>_<cl>.png, 32x32)
Global invImg10 = 0               ; pannello menu azioni (g10_2f.png)
Global shadeImg = 0               ; retino nero traslucido (shade.png, 40x70)
Global atkBase = 0                ; origine Y area attacco (ancorata al pad)
Global atkOX = 0                  ; origine X dei pannelli arma (= bordo sx pad)
Dim marchX(3), marchY(3), marchW(3), marchH(3)    ; celle normalizzate (px 2D)
Global marchN = 0, marchTW = 0, marchTH = 0       ; n. celle e ingombro totale
Dim palR(15), palG(15), palB(15)  ; palette ST (invgfx/palette.txt)
Global fw30X = 0, fw30Y = 0       ; destinazioni label (wRectPos950/958/966)
Global fw31X = 0, fw31Y = 0
Global fw32X = 0, fw32Y = 0
Global gClickR = 0                ; MouseHit(2) del frame (tasto destro)
Const VPOFFY  = 33                ; il viewport 2D inizia a y=33 dello schermo 320x200
                                  ; (mappa i rect dei bottoni, spazio mouse -> viewport)

; stato inventario dal push (le 4 righe in fondo, vedi EmitStateText nel bridge)
Global pcPot = 0                  ; PotentialCharacterOrdinal (provvisorio, menu resurrect)
Global pcSel = 0                  ; SelectedCharacterOrdinal (inventario aperto per N)
Global pcDRC = 0                  ; DisplayResurrectChestOrScroll (5 = menu resurrect)
Global pcKbd = 1                  ; keyboardMode (2 = name-entry REINCARNATE nel 2D)

; scheda live dal comando SHEET (header "SHEET <n>", 15 righe + 30 slot x 3)
Global shName$, shTitle$
Global shHP, shMaxHP, shStam, shMaxStam, shMana, shMaxMana
Global shLoad, shMaxLoad          ; in decimi di kg
Global shFood, shWater, shPoison, shOuches, shPot
Dim shReal(29)                    ; objectNameIndex reale (-1 = slot vuoto)
Dim shDraw(29)                    ; icona da disegnare (segnaposto gia' risolto)
Dim shFrame(29)                   ; cornice: 0=nessuna, 33/34/35
; pannello dell'occhio (16 righe extra in coda alla SHEET): 4 titoli mastery
; ("NOVICE FIGHTER", "-" = classe senza livello) + 6 attributi cur/max
; (STRENGTH..ANTI-FIRE; LUCK e' nascosto anche nel 2D)
Dim shEyeT$(3)
Dim shAtC(6), shAtM(6)            ; indici 1..6 come nel motore
Dim shAtN$(6)                     ; etichette (fisse, come Pointer16822)
Global shEyeOK = 0                ; coda occhio presente nell'ultima SHEET
Global sheetValid = 0             ; scheda ricevuta e coerente con pcSel
Global sheetPending = 0           ; SHEET inviato, risposta non ancora arrivata
Global needSheet = 0              ; un push richiede di (ri)chiedere la scheda

; grafiche vere della schermata (caricate da LoadInvGfx dopo la mappa)
Global invImg17 = 0               ; pannello/manichino (224x136, sfondo schermata)
Global invImg17prov = 0           ; come g17 ma senza i tasti salva/dormi/chiudi (provvisori)
Global invHand = 0                ; cursore-manina (hover su oggetto prendibile)
Global handObjImg = 0             ; icona dell'oggetto in mano, con sfondo grigio mascherato
Global handObjNI = -2             ; NI dell'oggetto attualmente in handObjImg (cache)
Global invImg40 = 0               ; pannello bottoni RESURRECT/RE-INCARNATE/CANCEL
Global invFrm33 = 0, invFrm34 = 0, invFrm35 = 0   ; cornici slot 18x18
Dim invAtl(6)                     ; 7 atlanti icone oggetti (256x32, celle 16x16)
Dim invW612(6)                    ; base objectNameIndex di ogni atlante (Word612)
Dim invIconX(45), invIconY(45)    ; IconDisplay: 46 posizioni (slot n -> entry n+8)
Global invG40X = 80, invG40Y = 52 ; destinazione di g40 nel viewport (wRectPos926)
Dim rrcCode(3), rrcX1(3), rrcX2(3), rrcY1(3), rrcY2(3)   ; bottoni (spazio mouse)
Global rrcN = 0
Global invReady = 0               ; tutte le grafiche/metadati caricati

Dim pliV(31)                      ; appoggio di ParseInts (numeri estratti da una riga)

; ----------------------------------------- attende la mappa (entra nel dungeon)
W = 0 : H = 0
While W <= 0 Or H <= 0
	If KeyHit(1) Then End
	If BridgeReq("MAP") >= 2 Then
		W = Int(brLine$(0))
		H = Int(brLine$(1))
		If W > 0 And W <= 64 And H > 0 And H <= 64 Then
			Dim grid$(H)
			For y = 0 To H-1
				grid$(y) = brLine$(2+y)
			Next
		Else
			W = 0 : H = 0      ; risposta incompleta, riprova
		EndIf
	EndIf
	If W <= 0 Then
		Cls
		Color 200,200,200
		Text 20, 200, "In attesa del gioco 2D (CSBwin, porta " + BRIDGE_PORT + ")..."
		Text 20, 220, "entra nel dungeon; ESC per uscire"
		Flip
		Delay 300
	EndIf
Wend

; finestra subito visibile: le 24 texture dei ritratti (1024x1024) richiedono
; qualche secondo, quindi mostriamo una schermata di caricamento invece del nero.
Cls
Color 200, 200, 200
Text 20, 210, "Caricamento dungeon..."
Flip

; ---------------------------------------------------------------- mondo 3D
camera = CreateCamera()
CameraViewport camera, 0, BARH, VIEW_W, 480  ; 3D sotto la barra HUD; a destra i comandi
CameraRange camera, 0.1, 2000
CameraClsColor camera, 0, 0, 0
; nebbia nera: piu' lontano = piu' scuro, fino al nero (look "buio" di DM)
CameraFogMode camera, 1
CameraFogColor camera, 0, 0, 0
CameraFogRange camera, CELL*0.5, CELL*3.0

; TORCIA: il party porta una torcia. Unica vera sorgente = una point light calda
; che segue la camera (posizionata ogni frame nel loop). L'ambient e' minimo: il
; dungeon e' buio, solo la torcia illumina (il rilievo dei muri vive di questa luce).
light = CreateLight(2)                 ; 2 = point light
LightColor light, 255, 190, 120        ; calda ma piu' luminosa (piu' lumen su G/B)
LightRange light, CELL*5.72            ; raggio ~2x +10%: torcia piu' intensa (att = (1-d/range)^2)
AmbientLight 16, 16, 16                ; quasi buio: base minima per non avere nero pieno
; la torcia e' tenuta in mano IN ALTO A DESTRA: figlia della camera con offset
; locale (X destra, Y su, Z avanti). Luce di taglio -> il rilievo dei muri/pavimento
; si vede. (colore/range vengono modulati ogni frame nel loop = fiamma.)
EntityParent light, camera
PositionEntity light, 0.9, 1.1, 0.2     ; offset locale rispetto alla camera
LightShadows light, True                ; ombre dinamiche dalla torcia

; texture muri
walltex = LoadTexture("test_new.png")
; normal map dei muri (derivata da test.png con gen_normalmap.py): aggiunge il
; rilievo per-pixel di pietre/malta. blend DOT3 (=4) -> lo shader la usa per
; perturbare la normale invece di colorare. Va nello slot 1, con la diffuse in slot 0.
walltexN = LoadTexture("test_n.png")
If walltexN <> 0 Then TextureBlend walltexN, 4
; stessa texture per gli stipiti, ma con UV scalate: la faccia stretta del palo
; (larghezza ~2*pw=0.54) mostra solo 0.54/CELL della texture -> stessa densita'
; di pietre dei muri, niente compressione. (pw=0.27 in BuildDoorFrame.)
jambtex = LoadTexture("test_new.png")
If jambtex <> 0 Then ScaleTexture jambtex, CELL/0.54, 1.0
; texture legno dell'anta (segnaposto generato: dm_wood.png); la geometria
; (bande + borchie) fornisce il rilievo, questa solo la venatura della superficie.
woodtex = LoadTexture("dm_wood.png")
irontex = LoadTexture("dm_iron.png")       ; metallo per la grata di ferro
; texture soffitto e pavimento (una piastrella per cella -> mapping di default).
; NB: niente piu' cuboni giganti per floor/ceiling (questo motore non li
;     renderizzava): pavimento e soffitto sono piastrelle costruite nel loop.
ceiltex  = LoadTexture("soffitto.png")
floortex = LoadTexture("pavi_3.png")
; normal map del pavimento (derivata da pavi_3.png): rilievo delle lastre. DOT3.
floortexN = LoadTexture("pavi_3_n.png")
If floortexN <> 0 Then TextureBlend floortexN, 4

; ante delle porte, indicizzate per cella (idx = y*W + x): l'entita' 3D, la sua
; quota di base (porta chiusa) e l'apertura corrente interpolata (0=chiusa..1=su).
Dim doorEnt(W*H)
Dim doorBaseY#(W*H)
Dim doorOpen#(W*H)
Dim doorTarget#(W*H)       ; apertura bersaglio dall'ultimo PUSH (0=chiusa..1=su)

; tipo di ogni porta (statico): letto UNA volta dal ponte (STATE via TCP) per
; scegliere lo stile in costruzione. -1 = sconosciuto (default: ferro).
Dim doorTypeAt(W*H)
For i = 0 To W*H-1 : doorTypeAt(i) = -1 : Next
If BridgeReq("STATE") >= 6 Then
	tk = 4                                           ; 0..3 = px,py,facing,level
	tmc = Int(brLine$(tk)) : tk = tk + 1 + tmc*3     ; salta i mostri (3 righe l'uno)
	tdc = Int(brLine$(tk)) : tk = tk + 1             ; n. porte
	For m = 1 To tdc
		ax  = Int(brLine$(tk))
		ay  = Int(brLine$(tk+1))                     ; tk+2 = state (ignorato qui)
		aty = Int(brLine$(tk+3))                     ; type
		tk = tk + 4
		If ax >= 0 And ax < W And ay >= 0 And ay < H Then doorTypeAt(ay*W+ax) = aty
	Next
EndIf

; ornateness per porta (statico): distingue porte dello stesso doorType (es. una
; grata liscia e una piena decorata). 0 = base liscia; >=1 = decoro (overlay DM).
; Comando DOORORN dedicato: blocchi (x, y, ornateness). Non tocca lo STATE caldo.
Dim doorOrnAt(W*H)
For i = 0 To W*H-1 : doorOrnAt(i) = 0 : Next
If BridgeReq("DOORORN") >= 1 Then
	odc = Int(brLine$(0)) : ok = 1
	For m = 1 To odc
		ax = Int(brLine$(ok))
		ay = Int(brLine$(ok+1))
		aor = Int(brLine$(ok+2))
		ok = ok + 3
		If ax >= 0 And ax < W And ay >= 0 And ay < H Then doorOrnAt(ay*W+ax) = aor
	Next
EndIf

; costruzione: muri (cubi alti) + pavimento/soffitto come piastrelle per cella
For y = 0 To H-1
	row$ = grid$(y)
	For x = 0 To W-1
		c$ = Mid$(row$, x+1, 1)
		wx# = (W-1-x)*CELL
		wz# = y*CELL
		If c$ = "0" Then
			; muro (cubo alto 0..CELL)
			wall = CreateCube()
			ScaleEntity wall, CELL/2.0, CELL/2.0, CELL/2.0
			PositionEntity wall, wx, CELL/2.0, wz
			EntityColor wall, 255, 255, 255
			If walltex  <> 0 Then EntityTexture wall, walltex,  0, 0   ; diffuse slot 0
			If walltexN <> 0 Then EntityTexture wall, walltexN, 0, 1   ; normal map slot 1
		Else
			; cella calpestabile: piastrella pavimento (y=0) + soffitto (y=CELL)
			ft = CreateCube()
			ScaleEntity ft, CELL/2.0, 0.1, CELL/2.0
			PositionEntity ft, wx, 0.0, wz
			If floortex <> 0 Then
				EntityColor ft, 255, 255, 255
				EntityTexture ft, floortex, 0, 0                       ; diffuse slot 0
				If floortexN <> 0 Then EntityTexture ft, floortexN, 0, 1 ; normal map slot 1
			Else
				EntityColor ft, 60, 60, 60
			EndIf

			ct = CreateCube()
			ScaleEntity ct, CELL/2.0, 0.1, CELL/2.0
			PositionEntity ct, wx, CELL, wz
			If ceiltex <> 0 Then
				EntityColor ct, 100, 100, 100   ; tinta scura: soffitto piu' buio
				EntityTexture ct, ceiltex
			Else
				EntityColor ct, 40, 40, 40
			EndIf

			; anta della porta (Fase A): pannello che riempie il varco, orientato
			; dagli stipiti (i due muri opposti). Resta chiuso finche' lo stato
			; live (dm_state.txt) non lo fa scorrere verso l'alto.
			If c$ = "4" Then
				; stipiti = muri ("0") adiacenti; il varco e' sull'asse opposto.
				wW = (x <= 0)   Or (Mid$(grid$(y), x, 1) = "0")
				wE = (x >= W-1) Or (Mid$(grid$(y), x+2, 1) = "0")
				wN = (y <= 0)   Or (Mid$(grid$(y-1), x+1, 1) = "0")
				wS = (y >= H-1) Or (Mid$(grid$(y+1), x+1, 1) = "0")

				; thinX = True se il varco e' est-ovest (stipiti a N e S):
				; anta sottile in X; altrimenti varco nord-sud, sottile in Z.
				thinX = (wN And wS) And Not (wW And wE)
				idx = y*W + x

				; anta IN GEOMETRIA su pivot che scorre su; stile da doorType+ornateness:
				; type 1 = legno; type 0 liscia (orn=0) = grata; type 0 decorata
				; (orn>=1) = porta piena metallica usurata (es. l'ingresso).
				dr = BuildDoor(wx, wz, thinX, doorTypeAt(idx), doorOrnAt(idx))

				doorEnt(idx)   = dr
				doorBaseY#(idx) = CELL/2.0
				doorOpen#(idx)  = 0.0                        ; parte chiusa

				BuildDoorFrame(wx, wz, thinX)               ; stipiti in pietra (statici)

				; door-cam di debug: memorizza la prima porta e una posa davanti.
				If DOORCAM And firstDoorPiv = 0 Then
					firstDoorPiv = dr
					; yaw nella convenzione del party: (2-facing)*90, facing 0=N 1=E 2=S 3=O
					If thinX Then
						fdCamZ# = wz
						If x < W-1 And Mid$(grid$(y), x+2, 1) <> "0" Then
							fdCamX# = (W-1-(x+1))*CELL   ; camera a est, guarda ovest
							fdCamYaw# = -90
						Else
							fdCamX# = (W-1-(x-1))*CELL   ; camera a ovest, guarda est
							fdCamYaw# = 90
						EndIf
					Else
						fdCamX# = wx
						If y < H-1 And Mid$(grid$(y+1), x+1, 1) <> "0" Then
							fdCamZ# = (y+1)*CELL         ; camera a sud, guarda nord
							fdCamYaw# = 180
						Else
							fdCamZ# = (y-1)*CELL         ; camera a nord, guarda sud
							fdCamYaw# = 0
						EndIf
					EndIf
				EndIf
			Else If c$ = "3" Then
				st = CreateCube()
				ScaleEntity st, CELL/3.0, 0.3, CELL/3.0
				PositionEntity st, wx, 0.6, wz
				EntityColor st, 200, 200, 60
			EndIf
		EndIf
	Next
Next

; anello di roccia attorno alla mappa: una cella-muro per ogni posizione FUORI
; griglia del perimetro. Cosi' il party su una cella di bordo che guarda fuori
; mappa vede la roccia (come il 2D) e non il fondo nero. Solo il bordo esterno.
For y = -1 To H
	For x = -1 To W
		If x < 0 Or x >= W Or y < 0 Or y >= H Then
			BuildRockWall((W-1-x)*CELL, y*CELL)
		EndIf
	Next
Next

; ------------------------------------------------ decorazioni di pavimento
; Il "ciuffo d'erba" (& simili): decorazioni PROCEDURALI del motore DM. NON sono
; oggetti a terra e NON stanno nella mappa: il 2D le calcola con un hash
; deterministico di (x,y,livello,dimensioni). Il bridge (comando DECOR) usa la
; STESSA funzione del motore e ci manda, per OGNI cella del livello: x, y,
; ordinale, graphicNumber. Le grafiche sono estratte offline in decorgfx/g<N>.png
; (comando DECORGFX del bridge + gfx_decode.py). Qui piazziamo un decal piatto,
; texturizzato con alpha, appena sopra il pavimento (top a Y=0.1).
Dim decoTex(1023)                 ; cache texture per numero-grafica (0 = non caricata)
If BridgeReq("DECOR") >= 1 Then
	dcount = Int(brLine$(0))
	dk = 1
	For i = 1 To dcount
		dx  = Int(brLine$(dk))
		dy  = Int(brLine$(dk+1))
		dgn = Int(brLine$(dk+3))               ; brLine$(dk+2) = ordinale (non serve qui)
		dk = dk + 4
		If dx >= 0 And dx < W And dy >= 0 And dy < H And dgn > 0 And dgn < 1024 Then
			If decoTex(dgn) = 0 Then
				; asset EDITABILE: se esiste decorgfx/g<N>_custom.png usiamo quello
				; (versione ridipinta a mano, mai sovrascritta dalla pipeline di
				; estrazione), altrimenti ripieghiamo sulla PNG auto-estratta.
				cust$ = "decorgfx/g" + dgn + "_custom.png"
				If FileType(cust$) = 1 Then
					decoTex(dgn) = LoadTexture(cust$, 2)                    ; flag 2 = alpha
				Else
					decoTex(dgn) = LoadTexture("decorgfx/g" + dgn + ".png", 2)
				EndIf
			EndIf
			If decoTex(dgn) <> 0 Then
				cxw# = (W-1-dx)*CELL           ; stessa proiezione riflessa su X della camera
				czw# = dy*CELL
				deco = CreateCube()
				ScaleEntity deco, CELL*0.364, 0.02, CELL*0.364  ; decal piatto (~mezzo tile, +30%)
				PositionEntity deco, cxw, 0.13, czw            ; appena sopra il pavimento
				EntityColor deco, 255, 255, 255
				EntityTexture deco, decoTex(dgn)
			EndIf
		EndIf
	Next
EndIf

; ------------------------------------------------------------- pedane a pavimento
; La "pedana" (pressure pad/dais) e' un attuatore con grafica di pavimento: nel 2D
; e' una floor-decoration, ma nel 3D la rendiamo come una LASTRA quadrata SOTTILE,
; texturizzata come il pavimento e appena rialzata. Celle dal bridge (PEDANE);
; centrata sul tile; lato 75% del tile (25% piu' piccola del quadrato del party).
Const PED_HW# = CELL*0.30    ; mezzo-lato: 60% del tile
Const PED_BH# = 0.035        ; mezzo-spessore del CORPO (bordo nero) - sporgenza contenuta
Const PED_TH# = 0.008        ; mezzo-spessore della LASTRA superiore
Const PED_TOPCOL = 200       ; luminosita' lastra superiore full-bright (255=piena)
If BridgeReq("PEDANE") >= 1 Then
	pncount = Int(brLine$(0))
	pk = 1
	For i = 1 To pncount
		pxc = Int(brLine$(pk))
		pyc = Int(brLine$(pk+1))
		pk = pk + 3                                    ; salta l'ordinale (riga pk+2)
		If pxc >= 0 And pxc < W And pyc >= 0 And pyc < H Then
			cxw# = (W-1-pxc)*CELL                       ; centro cella (riflesso su X)
			czw# = pyc*CELL
			; CORPO: pareti laterali grigio molto scuro, niente texture. Il suo top
			; (a 0.1+2*PED_BH) viene coperto dalla lastra, quindi si vedono solo i lati.
			pbody = CreateCube()
			ScaleEntity pbody, PED_HW, PED_BH, PED_HW
			PositionEntity pbody, cxw, 0.1 + PED_BH, czw   ; base appoggiata sul pavimento
			EntityColor pbody, 0, 0, 0                     ; bordo nero
			; LASTRA superiore: texture del pavimento, full-bright cosi' resta piu'
			; chiara del pavimento attenuato dalla torcia (~15% piu' chiara). La
			; luminosita' si regola con PED_TOPCOL (255 = piena; abbassa per meno).
			ptop = CreateCube()
			ScaleEntity ptop, PED_HW, PED_TH, PED_HW
			PositionEntity ptop, cxw, 0.1 + 2.0*PED_BH + PED_TH + 0.001, czw
			EntityColor ptop, PED_TOPCOL, PED_TOPCOL, PED_TOPCOL
			If floortex <> 0 Then
				EntityTexture ptop, floortex, 0, 0
			Else
				EntityColor ptop, PED_TOPCOL, PED_TOPCOL, PED_TOPCOL
			EndIf
			EntityFX ptop, 1                            ; full-bright: sopra piu' chiaro
		EndIf
	Next
EndIf

; ---------------------------------------------------- ritratti dei campioni
; Texture dei 24 campioni (immagini rifatte: portraits/00.png .. 23.png).
; ScaleTexture .,1,-1 corregge il flip verticale (origine immagine top-left
; vs OpenGL bottom-left): senza, i volti uscivano sottosopra.
Dim porttex(NPORT-1)
For i = 0 To NPORT-1
	; avanzamento visibile mentre carichiamo (il decode di 24 PNG e' lento)
	Cls
	Color 200, 200, 200
	Text 20, 200, "Caricamento ritratti  " + (i+1) + "/" + NPORT
	Color 80, 80, 80   : Rect 20, 222, 600, 12, 1
	Color 120, 200, 120 : Rect 20, 222, (i+1)*600/NPORT, 12, 1
	Flip
	n$ = Right$("0" + Str$(i), 2)            ; 0->"00", 5->"05", 23->"23"
	; versione ridotta 512x512 (NN_sm.png): caricamento piu' rapido e meno VRAM;
	; gli originali 1024x1024 (NN.png) restano per usi futuri.
	porttex(i) = LoadTexture("portraits/" + n$ + "_sm.png")
	If porttex(i) <> 0 Then ScaleTexture porttex(i), 1, -1
Next

; Posizioni REALI dal ponte (PORTRAITS via TCP): un ritratto per blocco di 5 righe
;   fx, fy (cella calpestabile) + dir muro (0=ovest 1=est 2=nord 3=sud) + ord
;   + act (0 = specchio gia' usato: nel 2D e' PORTRAIT_InActive, sbiadito)
; Coordinate griglia identiche alla mappa, quindi stessa proiezione della camera.
If BridgeReq("PORTRAITS") >= 1 Then
	pcount = Int(brLine$(0))
	pk = 1
	For i = 1 To pcount
		fx  = Int(brLine$(pk))
		fy  = Int(brLine$(pk+1))
		dir = Int(brLine$(pk+2))
		ord = Int(brLine$(pk+3))
		act = Int(brLine$(pk+4))
		pk = pk + 5
		If fx >= 0 And fx < W And fy >= 0 And fy < H Then
			cxw# = (W-1-fx)*CELL             ; mondo riflesso su X (come la camera)
			czw# = fy*CELL

			ox# = 0.0 : oz# = 0.0
			sx# = PORT_W/2.0 : sz# = PORT_W/2.0  ; uno dei due diventa lo spessore
			near# = CELL/2.0 - 0.12              ; appena davanti alla superficie
			If dir = 0 Then ox = +near : sx = PORT_T/2.0   ; muro a +X (griglia ovest)
			If dir = 1 Then ox = -near : sx = PORT_T/2.0   ; muro a -X (griglia est)
			If dir = 2 Then oz = -near : sz = PORT_T/2.0   ; muro a -Z (griglia nord)
			If dir = 3 Then oz = +near : sz = PORT_T/2.0   ; muro a +Z (griglia sud)

			; cornice scura, leggermente piu' grande e arretrata verso il muro
			fr = CreateCube()
			ScaleEntity fr, sx*1.18, (PORT_W/2.0)*1.18, sz*1.18
			PositionEntity fr, cxw + ox*1.04, EYE, czw + oz*1.04
			EntityColor fr, 30, 22, 14

			; il quadro col ritratto del campione
			pic = CreateCube()
			ScaleEntity pic, sx, PORT_W/2.0, sz
			PositionEntity pic, cxw + ox, EYE, czw + oz
			EntityColor pic, 255, 255, 255
			If ord >= 0 And ord < NPORT Then
				If porttex(ord) <> 0 Then EntityTexture pic, porttex(ord)
			EndIf
			EntityFX pic, 1                  ; full-bright: il volto resta leggibile

			; il quadro e' cliccabile: pick a box, il nome porta l'ordinale.
			; L'entity resta in portEnt(ord) per sbiadirla quando lo specchio
			; viene usato (act=0 subito, o al PORTS dopo un reclutamento)
			If ord >= 0 And ord < NPORT Then
				EntityPickMode pic, 3
				NameEntity pic, "champ:" + ord
				portEnt(ord) = pic
				SetPortraitActive(ord, act)
			EndIf
		EndIf
	Next
EndIf

; grafiche vere della schermata inventario (estratte dal bridge in invgfx/ e
; decodificate a scala 2). Se mancano, il pannello non si apre (invReady=0).
LoadInvGfx()

; ----------------------------------------------------- targhe di marmo (scritte)
; Le scritte sui muri di DM (sovraimpressione 2D nell'originale) diventano targhe
; di marmo incise. Posizioni REALI dal ponte (TEXTS via TCP, stesso formato dei
; ritratti: fx, fy, dir, testo). Le texture le genera gen_plaques.py:
;   plaque_0_<i>.png    marmo + scritta incisa (diffuse, slot 0)
;   plaque_0_<i>_n.png  normal map + height nell'alpha (slot 1, DOT3 -> rilievo/parallax)
; A differenza dei ritratti la lastra SPORGE di poco (PLAQ_OUT) e NON e' full-bright:
; cosi' la torcia illumina l'incisione e il rilievo si vede.
If BridgeReq("TEXTS") >= 1 Then
	xcount = Int(brLine$(0))
	xk = 1
	For i = 0 To xcount-1
		fx  = Int(brLine$(xk))
		fy  = Int(brLine$(xk+1))
		dir = Int(brLine$(xk+2))
		txt$ = brLine$(xk+3)            ; testo (gia' inciso nella texture, qui non serve)
		xk = xk + 4
		If fx >= 0 And fx < W And fy >= 0 And fy < H Then
			cxw# = (W-1-fx)*CELL            ; stessa proiezione riflessa su X della camera
			czw# = fy*CELL

			ox# = 0.0 : oz# = 0.0
			sx# = PLAQ_W/2.0 : sz# = PLAQ_W/2.0   ; uno dei due diventa lo spessore
			; centro lastra: faccia esterna a CELL/2 + PLAQ_OUT (sporge dal muro)
			near# = CELL/2.0 + PLAQ_OUT - PLAQ_T/2.0
			If dir = 0 Then ox = +near : sx = PLAQ_T/2.0   ; muro a +X (griglia ovest)
			If dir = 1 Then ox = -near : sx = PLAQ_T/2.0   ; muro a -X (griglia est)
			If dir = 2 Then oz = -near : sz = PLAQ_T/2.0   ; muro a -Z (griglia nord)
			If dir = 3 Then oz = +near : sz = PLAQ_T/2.0   ; muro a +Z (griglia sud)

			plaq = CreateCube()
			ScaleEntity plaq, sx, PLAQ_H/2.0, sz
			PositionEntity plaq, cxw + ox, EYE, czw + oz
			EntityColor plaq, 255, 255, 255

			pd$ = "plaque_0_" + i
			ptex = LoadTexture(pd$ + ".png")
			If ptex <> 0 Then
				; la texture e' top-left mentre GL campiona bottom-left: senza
				; correzione la scritta esce capovolta (righe invertite). Stesso
				; flip verticale dei ritratti (ScaleTexture 1,-1); l'orizzontale e'
				; gia' giusto (NON usare -1 su X o le lettere escono specchiate).
				ScaleTexture ptex, 1, -1
				EntityTexture plaq, ptex, 0, 0                 ; diffuse marmo slot 0
				pnrm = LoadTexture(pd$ + "_n.png")
				If pnrm <> 0 Then
					ScaleTexture pnrm, 1, -1                   ; stessa correzione della diffuse
					TextureBlend pnrm, 4                       ; DOT3: normal+height (parallax)
					EntityTexture plaq, pnrm, 0, 1             ; slot 1
				EndIf
			Else
				EntityColor plaq, 210, 205, 195                ; marmo di ripiego se manca la texture
			EndIf
		EndIf
	Next
EndIf

; ------------------------------------------------ decorazioni murali random
; Gli ornamenti PROCEDURALI dei muri di pietra (gancio, muschio, anello di
; legno, crepe...): il 2D li calcola per-faccia (bit 0-3 dei flags della
; cella-muro + hash RandomWallDecoration, vedi SummarizeStoneRoom). Il bridge
; (comando WALLDEC) replica la STESSA logica del motore e manda le facce
; decorate con la convenzione dei ritratti: fx, fy (cella calpestabile), dir
; del muro (0=O 1=E 2=N 3=S), tipo T, numero grafica e dimensioni 2D in px.
; Texture dalla libreria LOCALE walldec/t<T>.png (generata una tantum da
; wall_decode.py; walldec/t<T>_custom.png vince sempre, mai sovrascritta).
Const WDEC_T# = 0.02              ; mezzo-spessore del decal sul muro
Dim wdecTex(63)                   ; cache texture per tipo decorazione
If BridgeReq("WALLDEC") >= 1 Then
	wdcount = Int(brLine$(0))
	wdk = 1
	For i = 1 To wdcount
		fx  = Int(brLine$(wdk))
		fy  = Int(brLine$(wdk+1))
		dir = Int(brLine$(wdk+2))
		wdt = Int(brLine$(wdk+3))              ; tipo decorazione T
		wdw = Int(brLine$(wdk+5))              ; wdk+4 = graphicNumber (non serve)
		wdh = Int(brLine$(wdk+6))
		wdk = wdk + 7
		If wdw <= 0 Then wdw = 32
		If wdh <= 0 Then wdh = 32
		If fx >= 0 And fx < W And fy >= 0 And fy < H And wdt >= 0 And wdt < 64 Then
			; il GANCIO (tipo 4) e' in vera geometria 3D: placca d'ottone
			; inchiodata + gancio nero (BuildWallHook, dm3d_world.bb).
			; Gli altri tipi restano decal texturizzati.
			If wdt = 4 Then
				cxw# = (W-1-fx)*CELL
				czw# = fy*CELL
				hky# = CELL*0.62               ; placca sopra la mezzeria (come il 2D)
				hnear# = CELL/2.0 - 0.02
				If dir = 0 Then BuildWallHook(cxw + hnear, hky, czw, 90)
				If dir = 1 Then BuildWallHook(cxw - hnear, hky, czw, -90)
				If dir = 2 Then BuildWallHook(cxw, hky, czw - hnear, 0)
				If dir = 3 Then BuildWallHook(cxw, hky, czw + hnear, 180)
				Goto wdecNext
			EndIf
			If wdecTex(wdt) = 0 Then
				; asset EDITABILE: la versione ridipinta a mano vince sempre
				cust$ = "walldec/t" + wdt + "_custom.png"
				If FileType(cust$) = 1 Then
					wdecTex(wdt) = LoadTexture(cust$, 2)                 ; flag 2 = alpha
				Else
					wdecTex(wdt) = LoadTexture("walldec/t" + wdt + ".png", 2)
				EndIf
				; origine top-left del PNG vs campionamento bottom-left GL
				; (stesso flip verticale di targhe e ritratti)
				If wdecTex(wdt) <> 0 Then ScaleTexture wdecTex(wdt), 1, -1
			EndIf
			If wdecTex(wdt) <> 0 Then
				cxw# = (W-1-fx)*CELL           ; stessa proiezione riflessa su X della camera
				czw# = fy*CELL
				; ingombro dal 2D: il muro frontale riempie il viewport 224x136,
				; quindi w/224 e h/136 sono le frazioni di parete coperte
				dw# = (CELL * wdw) / 224.0 / 2.0
				dh# = (CELL * wdh) / 136.0 / 2.0
				ox# = 0.0 : oz# = 0.0
				sx# = dw : sz# = dw            ; uno dei due diventa lo spessore
				near# = CELL/2.0 - 0.10        ; appena davanti alla superficie del muro
				If dir = 0 Then ox = +near : sx = WDEC_T   ; muro a +X (griglia ovest)
				If dir = 1 Then ox = -near : sx = WDEC_T   ; muro a -X (griglia est)
				If dir = 2 Then oz = -near : sz = WDEC_T   ; muro a -Z (griglia nord)
				If dir = 3 Then oz = +near : sz = WDEC_T   ; muro a +Z (griglia sud)
				wdec = CreateCube()
				ScaleEntity wdec, sx, dh, sz
				PositionEntity wdec, cxw + ox, EYE, czw + oz
				EntityColor wdec, 255, 255, 255
				EntityTexture wdec, wdecTex(wdt)
			EndIf
		EndIf
		.wdecNext
	Next
EndIf

; ---------------------------------------------------------------- loop live
px = W/2 : py = H/2 : pf = 0      ; default finche' non leggiamo lo stato

; attiva il push: da qui in poi il server invia "PUSH <n>" + stato a ogni
; cambiamento (movimento party, porte, periodico) senza che noi chiediamo nulla.
BridgeReq("SUB")

; stato camera interpolato (movimento fluido)
camStarted = 0
cx# = 0.0 : cy# = EYE : cz# = 0.0 : cyaw# = 0.0

; --- stato torcia (fiamma): a SCATTI, non oscillazione continua. Per lo piu' arancio
; steady; OGNI TANTO una breve fiammata che aumenta luminosita' E vira al giallo limone.
torchNext = 0       ; ms del prossimo cambio di stato
torchFlare# = 0.0   ; 0 = baseline arancio .. 1 = fiammata gialla intensa
torchFlaring = 0    ; 0 = baseline, 1 = sta fiammeggiando

While Not KeyHit(1)
	; riconnessione (se CSBwin e' stato chiuso/riaperto): riattiva anche il push
	If bridge = 0 Then
		If BridgeConnect() <> 0 Then BridgeReq("SUB")
	EndIf

	; drena TUTTO cio' che il server ha inviato (PUSH di stato, "OK 0" dei CMD)
	; senza mai bloccare il frame: si legge solo se ci sono byte disponibili.
	While bridge <> 0 And ReadAvail(bridge) > 0
		hdr$ = ReadLine$(bridge)
		If Eof(bridge) Then
			CloseTCPStream bridge : bridge = 0     ; server sparito: riproveremo
		Else
			pn = BridgeRead(hdr$)
			If Left$(hdr$, 3) = "ERR" Then sheetPending = 0   ; SHEET rifiutato (o altro ERR)
			If Left$(hdr$, 5) = "SHEET" And pn >= 105 Then ParseSheet()
			If Left$(hdr$, 5) = "PORTS" And pn >= 1 Then ParsePorts()
			If Left$(hdr$, 4) = "PUSH" And pn >= 6 Then
				nx = Int(brLine$(0))
				ny = Int(brLine$(1))
				nf = Int(brLine$(2))
				lv = Int(brLine$(3))
				mvd = 0                         ; il party si e' mosso/girato?
				If nx <> px Or ny <> py Or nf <> pf Then mvd = 1
				If nx >= 0 And nx < W And ny >= 0 And ny < H Then
					px = nx : py = ny : pf = nf
				EndIf

				; sezione mostri: la saltiamo (3 righe per mostro), poi le porte.
				sk = 4
				mcount = Int(brLine$(sk)) : sk = sk + 1 + mcount*3

				; sezione porte: x, y, state (0=aperta..4=chiusa, 5=foro), type (0/1).
				dcount = Int(brLine$(sk)) : sk = sk + 1
				For m = 1 To dcount
					gx  = Int(brLine$(sk))
					gy  = Int(brLine$(sk+1))
					gst = Int(brLine$(sk+2))
					sk = sk + 4                     ; sk+3 = type (gia' noto)
					If gx >= 0 And gx < W And gy >= 0 And gy < H Then
						; apertura bersaglio: 4=chiusa->0, 0=aperta->1; 5 (foro)=aperta
						tOpen# = (4.0 - gst) / 4.0
						If gst >= 5 Then tOpen = 1.0
						If tOpen < 0.0 Then tOpen = 0.0
						If tOpen > 1.0 Then tOpen = 1.0
						doorTarget#(gy*W+gx) = tOpen
					EndIf
				Next

				; stato inventario/scheda (4 righe in fondo al push):
				; provvisorio, inventario aperto, cosa mostra il sotto-pannello,
				; name-entry in corso. Ogni push con inventario aperto rinfresca
				; la scheda (SHEET), cosi' barre/oggetti restano vivi.
				If sk+3 <= pn-1 Then
					pcPot = Int(brLine$(sk))
					pcSel = Int(brLine$(sk+1))
					pcDRC = Int(brLine$(sk+2))
					pcKbd = Int(brLine$(sk+3))
					If pcSel > 0 Then
						needSheet = 1
					Else
						sheetValid = 0        ; inventario chiuso: scheda scaduta
					EndIf
					; il party non e' piu' fermo davanti allo specchio (girato,
					; teletrasporto, evento del 2D) con la scheda del provvisorio
					; aperta: chiudi come se fosse stato premuto CANCEL — la
					; scheda non deve MAI restare in sovraimpressione
					If mvd And pcPot > 0 And pcSel > 0 And pcKbd <> 2 And bridge <> 0 Then
						WriteLine bridge, "RRC 162"
					EndIf

					; sezione CHAMPS (HUD): 4 slot x 17 righe + 6 globali
					; + 5 di stato spell (stadio + 4 rune incantation)
					sk = sk + 4
					If sk + 73 <= pn - 1 Then
						For ci = 0 To 3
							hcAct(ci)     = Int(brLine$(sk))
							hcName$(ci)   = brLine$(sk+1)
							hcPort(ci)    = Int(brLine$(sk+2))
							hcHP(ci)      = Int(brLine$(sk+3))
							hcMaxHP(ci)   = Int(brLine$(sk+4))
							hcStam(ci)    = Int(brLine$(sk+5))
							hcMaxStam(ci) = Int(brLine$(sk+6))
							hcMana(ci)    = Int(brLine$(sk+7))
							hcMaxMana(ci) = Int(brLine$(sk+8))
							hcPos(ci)     = Int(brLine$(sk+9))
							hcFacing(ci)  = Int(brLine$(sk+10))
							hcCol(ci)     = Int(brLine$(sk+11))
							hcShield(ci)  = Int(brLine$(sk+12))
							hcH0d(ci)     = Int(brLine$(sk+13))
							hcH0f(ci)     = Int(brLine$(sk+14))
							hcH1d(ci)     = Int(brLine$(sk+15))
							hcH1f(ci)     = Int(brLine$(sk+16))
							sk = sk + 17
						Next
						hcFire   = Int(brLine$(sk))
						hcSpell  = Int(brLine$(sk+1))
						hcParty  = Int(brLine$(sk+2))
						hcCaster = Int(brLine$(sk+3))
						hcHand   = Int(brLine$(sk+4))
						newHandNI = Int(brLine$(sk+5))
						; oggetto in mano cambiato (presa/posa): rinfresca subito
						; gli slot (il push non porta i 30 slot, serve la SHEET)
						If pcSel > 0 And newHandNI <> hcHandNI Then needSheet = 1
						hcHandNI = newHandNI
						; stato spell del caster (5 righe in coda, barra magie)
						If sk + 10 <= pn - 1 Then
							spStage = Int(brLine$(sk+6))
							spInc(0) = Int(brLine$(sk+7))
							spInc(1) = Int(brLine$(sk+8))
							spInc(2) = Int(brLine$(sk+9))
							spInc(3) = Int(brLine$(sk+10))
						EndIf
						; blocco ATTACK (HUD-3): 2 righe per slot (icona,
						; disabled) + Word20252, ordinale attaccante, numero
						; opzioni e 3 nomi azione
						If sk + 24 <= pn - 1 Then
							ak = sk + 11
							For ci = 0 To 3
								hcAtkNI(ci)  = Int(brLine$(ak))
								hcAtkDis(ci) = Int(brLine$(ak+1))
								ak = ak + 2
							Next
							atkPanels   = Int(brLine$(ak))
							atkOrd      = Int(brLine$(ak+1))
							atkNum      = Int(brLine$(ak+2))
							atkName$(0) = brLine$(ak+3)
							atkName$(1) = brLine$(ak+4)
							atkName$(2) = brLine$(ak+5)
						EndIf
						hudValid = 1

						; ritratti murali: al cambio di composizione del party
						; o alla chiusura del provvisorio (conferma o CANCEL)
						; qualche specchio puo' essersi spento -> refresh
						; asincrono ("PORTS <n>", gestito nel drenaggio)
						np = 0
						For ci = 0 To 3
							If hcAct(ci) Then np = np + 1
						Next
						If bridge <> 0 Then
							If (partySize >= 0 And np <> partySize) Or (prevPcPot > 0 And pcPot = 0) Then
								WriteLine bridge, "PORTS"
							EndIf
						EndIf
						partySize = np
						prevPcPot = pcPot
					EndIf
				EndIf
			EndIf
		EndIf
	Wend

	; scheda live: (ri)chiesta fuori dal drenaggio, una richiesta in volo alla volta
	If pcSel > 0 And needSheet And sheetPending = 0 And bridge <> 0 Then
		WriteLine bridge, "SHEET"
		sheetPending = 1 : needSheet = 0
	EndIf

	; animazione porte OGNI frame (i PUSH arrivano ~16/s: qui si interpola a 60):
	; scorrimento fluido del pivot verso il target (portcullis: sale).
	For idx = 0 To W*H-1
		dr = doorEnt(idx)
		If dr <> 0 Then
			doorOpen#(idx) = doorOpen#(idx) + (doorTarget#(idx) - doorOpen#(idx)) * DOOR_SMOOTH
			wxd# = (W-1-(idx Mod W))*CELL
			wzd# = (idx / W)*CELL
			PositionEntity dr, wxd, doorBaseY#(idx) + doorOpen#(idx)*CELL, wzd
		EndIf
	Next

	; --- target dalla cella/direzione del party ---
	; mondo riflesso su X (Blitz3D left-handed) -> worldX = (W-1-gridX)*CELL,
	; yaw = (2-pf)*90 (verificato: front/back e left/right combaciano col 2D).
	tx# = (W-1-px)*CELL
	ty# = EYE
	tz# = py*CELL
	tyaw# = (2 - pf) * 90

	If camStarted = 0 Then
		cx = tx : cy = ty : cz = tz : cyaw = tyaw   ; primo frame: nessuno scivolamento
		camStarted = 1
	Else
		; salto grande (teleport / cambio livello) -> snap, niente attraversamento muri
		If Abs(tx - cx) + Abs(tz - cz) > 1.6 * CELL Then
			cx = tx : cz = tz
		Else
			cx = cx + (tx - cx) * SMOOTH
			cz = cz + (tz - cz) * SMOOTH
		EndIf
		cy = ty
		; rotazione sul percorso piu' breve (avvolge a [-180,180))
		dyaw# = tyaw - cyaw
		dyaw = dyaw - 360.0 * Floor((dyaw + 180.0) / 360.0)
		cyaw = cyaw + dyaw * SMOOTH
	EndIf

	; --- idle sway: da fermo la camera "respira" leggermente; si attenua se muove ---
	moveAmt# = Abs(tx - cx) + Abs(tz - cz) + Abs(dyaw) * 0.02
	idleK# = 1.0 - moveAmt / (0.5 * CELL)
	If idleK < 0.0 Then idleK = 0.0
	If idleK > 1.0 Then idleK = 1.0
	t# = MilliSecs() * 0.001
	bobY#   = (Sin(t*55.0) * 0.030 + Sin(t*92.0 + 30) * 0.014) * idleK
	swayX#  = Sin(t*43.0 + 60) * 0.022 * idleK
	swayZ#  = Sin(t*37.0 + 200) * 0.022 * idleK
	idleYaw# = Sin(t*41.0 + 17) * 0.55 * idleK
	idlePit# = Sin(t*33.0 + 80) * 0.40 * idleK
	idleRol# = Sin(t*26.0 + 130) * 0.28 * idleK

	PositionEntity camera, cx + swayX, cy + bobY, cz + swayZ
	RotateEntity camera, idlePit, cyaw + idleYaw, idleRol
	; arretra la camera DIETRO lo sguardo, nel suo spazio LOCALE: cosi' l'offset di
	; arretramento RUOTA sempre insieme alla vista (MoveEntity = relativo all'orienta-
	; mento della camera, non al mondo). Verso il bordo posteriore della cella.
	MoveEntity camera, 0, 0, -CELL * 0.20

	; door-cam di debug: ignora il party e inquadra la prima porta del livello
	If DOORCAM And firstDoorPiv <> 0 Then
		PositionEntity camera, fdCamX, EYE, fdCamZ
		PointEntity camera, firstDoorPiv
	EndIf

	; (la torcia e' FIGLIA della camera con offset in alto a destra: vedi setup.
	;  Luce di TAGLIO rispetto allo sguardo, NON sull'occhio, cosi' i solchi del
	;  bump proiettano ombre visibili. Sull'occhio appiattirebbe il rilievo.)

	; fiamma a SCATTI: baseline arancio-giallo a lungo, poi una BREVE e LIEVE fiammata
	; (variazione max 4%), poi torna. Discontinua, non un seno continuo.
	ms = MilliSecs()
	If ms >= torchNext Then
		If torchFlaring Then
			torchFlare = Rnd(0.0, 0.18)        ; ritorno baseline (leggera variazione)
			torchNext  = ms + Rnd(900, 2600)   ; arancio steady A LUNGO (fiammate RARE)
			torchFlaring = 0
		Else
			torchFlare = Rnd(0.55, 1.0)        ; FIAMMATA: gialla e intensa
			torchNext  = ms + Rnd(45, 150)     ; breve
			torchFlaring = 1
		EndIf
	EndIf
	; colore base = arancio CHIARO virato al GIALLO (R max, G alto, B medio-basso).
	; la fiammata aggiunge una variazione MOLTO sottile: al massimo +4% (un filo piu'
	; luminosa e piu' gialla), poi torna. Niente sbalzi forti.
	fvar# = torchFlare * 0.04                   ; 0..0.04 -> tetto +4%
	LightColor light, 255, 235*(1.0+fvar), 210*(1.0+fvar)   ; bianco caldo (piu' chiaro)
	LightRange light, CELL * 6.6 * (1.0 + fvar)            ; +10% intensita' torcia; brillantezza +<=4% in fiammata
	RenderWorld

	; barra azioni nera a destra (il viewport 3D copre solo VIEW_W a sinistra)
	Color 0, 0, 0
	Rect VIEW_W, 0, BAR_W, WINH, True

	DrawHUD(pf) ; barra campioni in alto (tutta larghezza, sezione CHAMPS)
	DrawMagicBar() ; barra magie in cima alla colonna destra (HUD-4)
	DrawAttackPanel() ; pannelli arma / menu azioni sotto la barra magie (HUD-3)

	Color 255,255,255
	Text 2, WINH-34, "DM 3D  party (" + px + "," + py + ")  facing " + pf
	Text 2, WINH-18, "ESC per uscire"

	; un solo MouseHit per frame, condiviso fra pick 3D e pad (MoveUI)
	gClick = MouseHit(1)
	gClickR = MouseHit(2)
	cmx = MouseX() : cmy = MouseY()

	; click (sinistro O destro) su un box campione della barra in alto:
	; apre/chiude il suo inventario (codice coda 7+idx -> ShowHideInventory;
	; il motore lo ignora da solo se c'e' un provvisorio pendente)
	If (gClick > 0 Or gClickR > 0) And cmy < BARH Then
		ci = cmx / Int(69*INVSC)
		If ci >= 0 And ci <= 3 And bridge <> 0 Then
			If hcAct(ci) Then WriteLine bridge, "CMD " + (7 + ci)
		EndIf
	EndIf

	; click nel viewport 3D: col pannello chiuso, il click sul quadro del
	; ritratto ESEGUE davvero il click sullo specchio (MIRROR -> AddCharacter:
	; il campione entra provvisorio nel party e ANCHE il 2D apre l'inventario);
	; col pannello aperto, i click vanno ai bottoni RESURRECT/RE-INCARNATE/CANCEL.
	If gClick > 0 And cmx < VIEW_W And cmy >= BARH Then
		If pcSel > 0 Then
			; Campione CONFERMATO: la X in alto a destra chiude l'inventario
			; (codice coda 7+idx, ShowHideInventory nel motore). Col PROVVISORIO
			; quei 3 tasti (salva/dormi/chiudi) sono nascosti, quindi la loro
			; area NON deve fare nulla: il CANCEL resta sul bottone del pannello
			; resurrect (g40) gestito da RRCClick.
			If pcPot = 0 Then
				xx1 = INVX0 + Int(210*INVSC) : xx2 = INVX0 + Int(224*INVSC)
				xy1 = INVY0 + Int(1*INVSC)   : xy2 = INVY0 + Int(14*INVSC)
				If cmx >= xx1 And cmx < xx2 And cmy >= xy1 And cmy < xy2 And bridge <> 0 Then
					WriteLine bridge, "CMD " + (7 + pcSel - 1)
				Else
					; drag-and-drop: click su uno slot -> prendi/posa l'oggetto
					; (il motore 2D fa lo swap objectInHand<->slot via GRAB)
					InvSlotClick(cmx, cmy)
				EndIf
			Else
				If pcDRC = 5 And pcKbd <> 2 Then RRCClick(cmx, cmy)
			EndIf
		Else
			; CameraPick vuole coordinate RELATIVE al viewport (l'engine divide
			; per vp_w/vp_h senza sottrarre l'origine): tolta la barra in alto
			pk = CameraPick(camera, cmx, cmy - BARH)
			If pk <> 0 Then
				nm$ = EntityName(pk)
				If Left$(nm$, 6) = "champ:" Then
					; solo da vicino, come nel 2D si clicca lo specchio davanti
					; (il bridge ricontrolla comunque che lo specchio sia DAVANTI)
					If EntityDistance(camera, pk) < CELL*2.6 Then
						co = Int(Mid$(nm$, 7))
						If co >= 0 And co < NPORT And bridge <> 0 Then
							WriteLine bridge, "MIRROR " + co
						EndIf
					EndIf
				EndIf
			EndIf
		EndIf
	EndIf

	MagicUI()   ; click sulla barra magie (linguette caster, rune, cast)
	AttackUI()  ; click sui pannelli arma / menu azioni ("ATTACK <code>")
	MoveUI()    ; frecce di movimento cliccabili (inviano "CMD <n>" via TCP a CSBwin)

	; la VERA schermata dello specchio, pilotata dallo stato del 2D
	If pcSel > 0 And invReady Then DrawInvPanel()

	; cursore drag-and-drop SOPRA il pannello (oggetto in mano / manina in hover)
	If pcSel > 0 And invReady Then DrawInvCursor()

	shotN = shotN + 1
	If SHOT And shotN = 45 Then
		SaveBuffer BackBuffer(), "dm3d_shot.bmp"
		End
	EndIf

	Flip
Wend
End

; --------------------------------------------------------------------------
; Funzioni raggruppate in sotto-file (3d_include/). Inclusione TESTUALE:
; condividono tutti i globals/const/dim dichiarati sopra. I path sono
; relativi alla cwd del compilatore (play_DungeonMaster/), come i LoadImage
; a runtime: compila SEMPRE da play_DungeonMaster/.
; --------------------------------------------------------------------------
Include "3d_include/dm3d_bridge.bb"
Include "3d_include/dm3d_inv.bb"
Include "3d_include/dm3d_ui.bb"
Include "3d_include/dm3d_world.bb"
