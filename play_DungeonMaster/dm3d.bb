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
Global partySize = -1      ; n. slot attivi visto nell'ultimo push (per PORTS)
Global prevPcPot = 0       ; pcPot del push precedente (conferma/cancel -> PORTS)
Const PORT_W# = CELL*0.55  ; larghezza/altezza del quadro appeso al muro
Const PORT_T# = 0.10       ; spessore della tavola (sottile)

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
LightRange light, CELL*5.2             ; raggio ~2x: torcia piu' intensa (att = (1-d/range)^2)
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

				; anta IN GEOMETRIA su pivot che scorre su; stile dal doorType:
				; 0 (e sconosciuto) = ferro a grata (con buchi); 1 = legno.
				dr = BuildDoor(wx, wz, thinX, doorTypeAt(idx))

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
	MoveEntity camera, 0, 0, -CELL * 0.45

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
	LightRange light, CELL * 6.0 * (1.0 + fvar)             ; brillantezza +<=4% in fiammata
	RenderWorld

	; barra azioni nera a destra (il viewport 3D copre solo VIEW_W a sinistra)
	Color 0, 0, 0
	Rect VIEW_W, 0, BAR_W, WINH, True

	DrawHUD(pf) ; barra campioni in alto (tutta larghezza, sezione CHAMPS)
	DrawMagicBar() ; barra magie in cima alla colonna destra (HUD-4)

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

; =========================================================================
; CLIENT TCP DEL PONTE
; =========================================================================
; apre (o riapre) la connessione al server del bridge dentro CSBwin.
; Ritorna lo stream (0 se il server non c'e': si riprova al giro dopo).
Function BridgeConnect()
	If bridge <> 0 Then Return bridge
	bridge = OpenTCPStream(BRIDGE_HOST, BRIDGE_PORT)
	Return bridge
End Function

; invia un comando e legge la risposta framed ("OK <n>" + n righe in brLine$).
; Ritorna n (>=0) oppure -1 (ERR dal server, o connessione assente/caduta).
; BLOCCANTE per la durata della risposta (ok: risposte piccole, server a 10ms).
Function BridgeReq(cmd$)
	brN = -1
	If BridgeConnect() = 0 Then Return -1
	WriteLine bridge, cmd$
	hdr$ = ReadLine$(bridge)
	If Eof(bridge) Then                        ; server sparito a meta' richiesta
		CloseTCPStream bridge : bridge = 0
		Return -1
	EndIf
	Return BridgeRead(hdr$)
End Function

; legge il payload di una risposta/push gia' intestata (hdr$ = "OK n"/"PUSH n")
; dentro brLine$. Ritorna n; -1 se l'header e' un ERR o non riconosciuto.
Function BridgeRead(hdr$)
	brN = -1
	If Left$(hdr$, 2) <> "OK" And Left$(hdr$, 4) <> "PUSH" And Left$(hdr$, 5) <> "SHEET" And Left$(hdr$, 5) <> "PORTS" Then Return -1
	sp = Instr(hdr$, " ")
	If sp = 0 Then Return -1
	n = Int(Mid$(hdr$, sp+1))
	If n < 0 Then n = 0
	If n > 8191 Then n = 8191                  ; hard cap (mai col protocollo attuale)
	For i = 0 To n-1
		brLine$(i) = ReadLine$(bridge)
	Next
	brN = n
	Return n
End Function

; =========================================================================
; PAD DI MOVIMENTO (canale 3D -> 2D)
; Frecce cliccabili in basso a destra. Al click inviano "CMD <codice>" al
; bridge via TCP; CSBwin lo inietta nella coda mouse come azione gia'
; risolta -> il party si muove anche nel 2D. La risposta "OK 0" viene
; drenata dal loop principale insieme ai PUSH.
;   1=gira sx  2=gira dx  3=avanti  4=strafe dx  5=indietro  6=strafe sx
; =========================================================================
; Pad di movimento: disegna la grafica VERA di DM (mvbuttons.bmp, grafica 13
; estratta da CSBwin) in basso a destra, e fa l'hit-test della griglia 3x2.
; Layout (come DM):  [gira-sx][avanti][gira-dx] / [strafe-sx][indietro][strafe-dx]
Function MoveUI()
	If mvImg = 0 Then mvImg = LoadImage("mvbuttons.png")   ; PNG con alpha: nero gia' trasparente, 2x nearest (netto)
	If mvImgDark = 0 Then mvImgDark = LoadImage("mvbuttons_dark.png")
	If hovImg = 0 Then hovImg = LoadImage("hover.png")     ; tinta bianca semi-trasparente per l'hover
	If mvImg = 0 Then Return            ; immagine non trovata: nessun pad

	iw = ImageWidth(mvImg) : ih = ImageHeight(mvImg)
	px = VIEW_W + (BAR_W - iw)/2      ; centrato nella barra destra
	py = WINH - ih - 14              ; in fondo alla barra (sopra: spazio per la scheda PG)
	cw = iw/3 : ch = ih/2            ; dimensione cella (per hover e hit-test)

	; scheda campione aperta: come nel 2D i tasti restano visibili ma SCURITI
	; e non rispondono ai click (l'utente capisce che deve fare una scelta)
	If pcSel > 0 Then
		If mvImgDark <> 0 Then
			DrawImage mvImgDark, px, py
		Else
			DrawImage mvImg, px, py
		EndIf
		Return
	EndIf

	mx = MouseX() : my = MouseY() : clk = gClick   ; MouseHit letto UNA volta nel loop
	over = (mx>=px And mx<px+iw And my>=py And my<py+ih)

	; HOVER: schiarisce leggermente il fondo della cella sotto il mouse,
	; disegnato PRIMA delle frecce (cosi' le frecce restano nitide sopra)
	If over And hovImg <> 0 Then
		DrawImage hovImg, px + ((mx-px)/cw)*cw, py + ((my-py)/ch)*ch
	EndIf

	DrawImage mvImg, px, py

	If clk > 0 And over Then
		code = MvCode((mx-px)/cw, (my-py)/ch)
		If code > 0 Then SendCmd(code)
	EndIf
End Function

; mappa cella (colonna,riga) -> codice movimento
Function MvCode(col, row)
	If row = 0 Then
		If col = 0 Then Return 1     ; gira sinistra
		If col = 1 Then Return 3     ; avanti
		If col = 2 Then Return 2     ; gira destra
	Else
		If col = 0 Then Return 6     ; strafe sinistra
		If col = 1 Then Return 5     ; indietro
		If col = 2 Then Return 4     ; strafe destra
	EndIf
	Return 0
End Function

; invia il comando di movimento a CSBwin via TCP (la "OK 0" la drena il loop)
Function SendCmd(code)
	If BridgeConnect() <> 0 Then WriteLine bridge, "CMD " + code
End Function

; =========================================================================
; SCHERMATA INVENTARIO AUTENTICA (la vera schermata dello specchio di DM)
; Disegnata con le grafiche VERE del gioco estratte dal bridge (invgfx/):
; manichino (g17), oggetti negli slot alle posizioni IconDisplay, cornici
; 33/34/35, barre HEALTH/STAMINA/MANA, LOAD in kg col colore giusto, e il
; pannello RESURRECT/RE-INCARNATE/CANCEL (g40) quando il campione e'
; provvisorio. I dati arrivano dal comando SHEET (gia' risolti dal motore:
; slot vuoti = sagome segnaposto, chest/pergamena aperta, ouch, ecc.).
; =========================================================================

; estrae fino a 32 interi da una riga "etichetta a b c ..." in pliV(); ritorna
; quanti. Un numero vale solo come TOKEN a se' (preceduto da inizio riga o
; spazio): le cifre dentro le etichette ("g40", "word612") NON sono numeri.
Function ParseInts(ln$)
	n = 0
	i = 1
	L = Len(ln$)
	While i <= L And n < 32
		c$ = Mid$(ln$, i, 1)
		If c$ = " " Then
			i = i + 1
		Else
			; inizio di un token: trova la fine
			j = i
			While j < L And Mid$(ln$, j+1, 1) <> " "
				j = j + 1
			Wend
			t$ = Mid$(ln$, i, j-i+1)
			; e' un numero solo se TUTTO il token e' cifre (con '-' opzionale)
			ok = 1
			For k = 1 To Len(t$)
				ch$ = Mid$(t$, k, 1)
				If Not ((ch$ >= "0" And ch$ <= "9") Or (k = 1 And ch$ = "-")) Then ok = 0
			Next
			If ok And t$ <> "-" Then
				pliV(n) = Int(t$)
				n = n + 1
			EndIf
			i = j + 1
		EndIf
	Wend
	Return n
End Function

; carica grafiche e metadati della schermata inventario (invgfx/, scala 2).
; invReady resta 0 se manca qualcosa: il pannello semplicemente non si apre.
Function LoadInvGfx()
	invImg17 = LoadImage("invgfx/g17.png")
	; variante SENZA i 3 bottoni salva/dormi/chiudi in alto a destra: si usa
	; per i campioni PROVVISORI (pcPot>0), che non fanno ancora parte del gruppo
	; e quindi non devono vedere quei tasti (come il 2D, CSBCode.cpp:7494 che
	; ricopre col grigio pannello se PotentialCharacterOrdinal). Opzionale.
	invImg17prov = LoadImage("invgfx/g17_prov.png")
	invHand = LoadImage("invgfx/hand.png")   ; cursore-manina in hover
	invImg40 = LoadImage("invgfx/g40.png")
	; atlanti e cornici nelle versioni PRE-SPECCHIATE (gNf.png): DrawImageRect
	; campiona il rettangolo sorgente dall'immagine specchiata verticalmente
	; (origine GL, vedi gfx_decode.py), quindi dal file capovolto esce dritto.
	invFrm33 = LoadImage("invgfx/g33f.png")
	invFrm34 = LoadImage("invgfx/g34f.png")
	invFrm35 = LoadImage("invgfx/g35f.png")
	; HUD: box campione morto + scudi (colorkey gia' applicato in decodifica)
	invImg8  = LoadImage("invgfx/g8f.png")
	invImg37 = LoadImage("invgfx/g37f.png")
	invImg38 = LoadImage("invgfx/g38f.png")
	invImg39 = LoadImage("invgfx/g39f.png")
	; pannello FOOD/WATER (interi via DrawImage: niente versione flippata)
	invImg20 = LoadImage("invgfx/g20.png")
	invImg30 = LoadImage("invgfx/g30.png")
	invImg31 = LoadImage("invgfx/g31.png")
	invImg32 = LoadImage("invgfx/g32.png")
	For i = 0 To 3
		invArrow(i) = LoadImage("invgfx/g28c" + i + ".png")   ; figurine pos/dir
	Next
	; font originale DM (cella 16 = round(6*2.75); frame = codice ASCII)
	fntGrey   = LoadAnimImage("invgfx/font_grey.png",   16, 16, 0, 128)
	fntOrange = LoadAnimImage("invgfx/font_orange.png", 16, 16, 0, 128)
	fntYellow = LoadAnimImage("invgfx/font_yellow.png", 16, 16, 0, 128)
	fntRed    = LoadAnimImage("invgfx/font_red.png",    16, 16, 0, 128)
	fntGreen  = LoadAnimImage("invgfx/font_green.png",  16, 16, 0, 128)

	; etichette del pannello occhio (fisse in inglese, come Pointer16822)
	shAtN$(1) = "STRENGTH"   : shAtN$(2) = "DEXTERITY"
	shAtN$(3) = "WISDOM"     : shAtN$(4) = "VITALITY"
	shAtN$(5) = "ANTI-MAGIC" : shAtN$(6) = "ANTI-FIRE"

	; barra magie: font a scala 2 (cella 12). NB: la grafica 9 NON si usa —
	; e' solo lo sfondo di primo impianto del 2D (tab piena + due riquadroni),
	; ma a schermo il 2D vero mostra 6 QUADRATI singoli per le rune, il
	; riquadro incantation e il quadrato del backspace: sono i rect dei
	; bottoni di Buttons18060 (gia' nel layout come "mbutton"), quindi i
	; bordi si disegnano nativamente in DrawMagicBar.
	fntCyan2  = LoadAnimImage("invgfx/font_cyan2.png",  12, 12, 0, 128)
	fntBlack2 = LoadAnimImage("invgfx/font_black2.png", 12, 12, 0, 128)

	; palette ST del gioco (per il colore-campione del marching order)
	f = ReadFile("invgfx/palette.txt")
	If f <> 0 Then
		For i = 0 To 15
			If Not Eof(f) Then
				If ParseInts(ReadLine$(f)) >= 3 Then
					palR(i) = pliV(0) : palG(i) = pliV(1) : palB(i) = pliV(2)
				EndIf
			EndIf
		Next
		CloseFile f
	EndIf
	; mini-ritratti ORIGINALI DM 88x80 (portraits_decode.py 2.75 hud_);
	; file piccolissimi: caricarli tutti e' istantaneo (vedi ANALISI_HUD_3D §6)
	For o = 0 To 23
		hudPort(o) = LoadImage("portraits/hud_" + Right$("0" + Str$(o), 2) + ".png")
	Next
	okAtl = 1
	For a = 0 To 6
		invAtl(a) = LoadImage("invgfx/g" + (42+a) + "f.png")
		If invAtl(a) = 0 Then okAtl = 0
		invW612(a) = a*32                  ; default; sovrascritto da layout.txt
	Next

	; posizioni IconDisplay: 46 righe "x y" (slot inventario n -> entry n+8)
	okIcon = 0
	f = ReadFile("invgfx/icondisplay.txt")
	If f <> 0 Then
		For i = 0 To 45
			If Not Eof(f) Then
				If ParseInts(ReadLine$(f)) >= 2 Then
					invIconX(i) = pliV(0)
					invIconY(i) = pliV(1)
					okIcon = okIcon + 1
				EndIf
			EndIf
		Next
		CloseFile f
	EndIf

	; layout: destinazione di g40 nel viewport + bottoni RRC (spazio mouse) + Word612
	f = ReadFile("invgfx/layout.txt")
	If f <> 0 Then
		While Not Eof(f)
			ln$ = ReadLine$(f)
			n = ParseInts(ln$)
			If Left$(ln$, 3) = "g40" And n >= 4 Then
				invG40X = pliV(0)              ; x1 (pliV: x1 x2 y1 y2)
				invG40Y = pliV(2)              ; y1
			Else If Left$(ln$, 3) = "g30" And n >= 4 Then
				fw30X = pliV(0) : fw30Y = pliV(2)
			Else If Left$(ln$, 3) = "g31" And n >= 4 Then
				fw31X = pliV(0) : fw31Y = pliV(2)
			Else If Left$(ln$, 3) = "g32" And n >= 4 Then
				fw32X = pliV(0) : fw32Y = pliV(2)
			Else If Left$(ln$, 6) = "button" And n >= 5 And rrcN < 4 Then
				rrcCode(rrcN) = pliV(0)
				rrcX1(rrcN) = pliV(1) : rrcX2(rrcN) = pliV(2)
				rrcY1(rrcN) = pliV(3) : rrcY2(rrcN) = pliV(4)
				rrcN = rrcN + 1
			Else If Left$(ln$, 5) = "march" And n >= 4 And marchN < 4 Then
				; celle del quadrato marching-order (x1 x2 y1 y2, spazio 2D)
				marchX(marchN) = pliV(0) : marchW(marchN) = pliV(1) - pliV(0)
				marchY(marchN) = pliV(2) : marchH(marchN) = pliV(3) - pliV(2)
				marchN = marchN + 1
			Else If Left$(ln$, 7) = "mbutton" And n >= 5 And magN < 9 Then
				; bottoni barra magie (code x1 x2 y1 y2, spazio mouse 2D)
				magCode(magN) = pliV(0)
				magBX1(magN) = pliV(1) : magBX2(magN) = pliV(2)
				magBY1(magN) = pliV(3) : magBY2(magN) = pliV(4)
				magN = magN + 1
			Else If Left$(ln$, 6) = "word12" And n >= 4 Then
				; rect del pannello magia g9 (Word12: x1 x2 y1 y2)
				mag12X = pliV(0) : mag12Y = pliV(2)
			Else If Left$(ln$, 7) = "word612" Then
				For a = 0 To 6
					If a < n Then invW612(a) = pliV(a)
				Next
			EndIf
		Wend
		CloseFile f
	EndIf

	; normalizza le celle marching-order sull'origine comune (il 3D le
	; ricolloca dove vuole, conta solo la geometria relativa fra le 4)
	If marchN > 0 Then
		mx0 = marchX(0) : my0 = marchY(0)
		For i = 1 To marchN-1
			If marchX(i) < mx0 Then mx0 = marchX(i)
			If marchY(i) < my0 Then my0 = marchY(i)
		Next
		For i = 0 To marchN-1
			marchX(i) = marchX(i) - mx0
			marchY(i) = marchY(i) - my0
			If marchX(i) + marchW(i) > marchTW Then marchTW = marchX(i) + marchW(i)
			If marchY(i) + marchH(i) > marchTH Then marchTH = marchY(i) + marchH(i)
		Next
	EndIf

	If invImg17 <> 0 And invImg40 <> 0 And okAtl And okIcon = 46 And rrcN >= 3 Then
		invReady = 1
	EndIf
End Function

; risposta "SHEET <n>" gia' in brLine$: 15 righe di testa + 30 slot x 3 righe
Function ParseSheet()
	shName$   = brLine$(0)
	shTitle$  = brLine$(1)
	shHP      = Int(brLine$(2))  : shMaxHP   = Int(brLine$(3))
	shStam    = Int(brLine$(4))  : shMaxStam = Int(brLine$(5))
	shMana    = Int(brLine$(6))  : shMaxMana = Int(brLine$(7))
	shLoad    = Int(brLine$(8))  : shMaxLoad = Int(brLine$(9))
	shFood    = Int(brLine$(10)) : shWater   = Int(brLine$(11))
	shPoison  = Int(brLine$(12)) : shOuches  = Int(brLine$(13))
	shPot     = Int(brLine$(14))
	For i = 0 To 29
		shReal(i)  = Int(brLine$(15 + i*3))
		shDraw(i)  = Int(brLine$(16 + i*3))
		shFrame(i) = Int(brLine$(17 + i*3))
	Next
	; coda del pannello occhio (bridge nuovo: 105 + 4 titoli + 12 valori)
	shEyeOK = 0
	If brN >= 121 Then
		For i = 0 To 3
			shEyeT$(i) = brLine$(105 + i)
		Next
		For i = 1 To 6
			shAtC(i) = Int(brLine$(109 + (i-1)*2))
			shAtM(i) = Int(brLine$(110 + (i-1)*2))
		Next
		shEyeOK = 1
	EndIf
	sheetValid = 1
	sheetPending = 0
End Function

; disegna l'icona oggetto (cella 16x16 di un atlante 42..48) a scala INVSC
Function DrawInvIcon(ni, dx, dy)
	If ni < 0 Then Return
	a = -1
	For i = 0 To 6                          ; atlante: maggiore base <= ni
		If ni >= invW612(i) Then a = i
	Next
	If a < 0 Then Return
	cl = ni - invW612(a)                    ; cella nell'atlante (16 colonne)
	If cl < 0 Or cl > 31 Then Return
	; coordinate top-origin normali: l'atlante caricato e' il gNf.png gia'
	; pre-specchiato (compensa il campionamento specchiato di DrawImageRect).
	; 16*2.75 = 44 pixel esatti: le celle non sbavano mai su quelle vicine.
	cw = Int(16*INVSC)
	sx = (cl Mod 16) * cw
	sy = (cl / 16) * cw
	DrawImageRect invAtl(a), dx, dy, sx, sy, cw, cw
End Function

Function DrawInvPanel()
	; sfondo opaco dietro al pannello (gli indici-0 dei PNG sono trasparenti)
	Color 0, 0, 0
	Rect INVX0-4, INVY0-4, 224*INVSC+8, 136*INVSC+8, True
	; provvisorio (non ancora nel gruppo): pannello SENZA i 3 tasti in alto a
	; destra (salva/dormi/chiudi); confermato: pannello normale con i tasti
	If pcPot > 0 And invImg17prov <> 0 Then
		DrawImage invImg17prov, INVX0, INVY0
	Else
		DrawImage invImg17, INVX0, INVY0
	EndIf

	If sheetValid = 0 Then Return           ; scheda non ancora arrivata

	; nome e titolo nella riga alta del viewport, font DM (come il 2D:
	; baseline y=7 -> top 2; arancio se possiede il cursore-mano)
	If hcHand = pcSel-1 Then
		DMText(INVX0 + Int(2*INVSC), INVY0 + Int(2*INVSC), shName$ + " " + shTitle$, fntOrange)
	Else
		DMText(INVX0 + Int(2*INVSC), INVY0 + Int(2*INVSC), shName$ + " " + shTitle$, fntGrey)
	EndIf

	; oggetti negli slot (0..29): cornice 33/34/35 poi icona, come il 2D
	; (DisplayBackpackItem: cornice a (x-1,y-1) 18x18, icona a (x,y) 16x16)
	; Int() sulle dimensioni: 18*2.75 = 49.5 arrotonderebbe a 50, un pixel
	; OLTRE l'immagine della cornice (49) -> lettura fuori dai bordi
	fd = Int(18*INVSC)
	For i = 0 To 29
		ix = invIconX(i+8) : iy = invIconY(i+8)
		fx = INVX0 + Int((ix-1)*INVSC) : fy = INVY0 + Int((iy-1)*INVSC)
		fi = shFrame(i)
		If fi = 33 Then DrawImageRect invFrm33, fx, fy, 0, 0, fd, fd
		If fi = 34 Then DrawImageRect invFrm34, fx, fy, 0, 0, fd, fd
		If fi = 35 Then DrawImageRect invFrm35, fx, fy, 0, 0, fd, fd
		DrawInvIcon(shDraw(i), INVX0+Int(ix*INVSC), INVY0+Int(iy*INVSC))
	Next

	; HEALTH/STAMINA/MANA col font DM, formato IDENTICO al 2D
	; (PrintLifeForce: valore 3 cifre allineato a destra a x=55, "/" a 73,
	; massimo a 79 -> stringa "cur/max" con RSet; righe 116/124/132 =
	; baseline -> top = riga-5; STAMINA mostrata /10)
	DMText(INVX0 + Int(5*INVSC),  INVY0 + Int(111*INVSC), "HEALTH", fntGrey)
	DMText(INVX0 + Int(55*INVSC), INVY0 + Int(111*INVSC), RSet$(Str$(shHP),3) + "/" + RSet$(Str$(shMaxHP),3), fntGrey)
	DMText(INVX0 + Int(5*INVSC),  INVY0 + Int(119*INVSC), "STAMINA", fntGrey)
	DMText(INVX0 + Int(55*INVSC), INVY0 + Int(119*INVSC), RSet$(Str$(shStam/10),3) + "/" + RSet$(Str$(shMaxStam/10),3), fntGrey)
	DMText(INVX0 + Int(5*INVSC),  INVY0 + Int(127*INVSC), "MANA", fntGrey)
	DMText(INVX0 + Int(55*INVSC), INVY0 + Int(127*INVSC), RSet$(Str$(shMana),3) + "/" + RSet$(Str$(shMaxMana),3), fntGrey)

	; LOAD in kg (valori in decimi): label a x=104, valore a x=148 (2D);
	; giallo oltre i 5/8 del massimo, rosso in sovraccarico
	fnt = fntGrey
	If shLoad > shMaxLoad Then
		fnt = fntRed
	Else If 8*shLoad > 5*shMaxLoad Then
		fnt = fntYellow
	EndIf
	ld$ = RSet$(Str$(shLoad/10),3) + "." + (shLoad Mod 10) + "/" + RSet$(Str$((shMaxLoad+5)/10),3) + " KG"
	DMText(INVX0 + Int(104*INVSC), INVY0 + Int(127*INVSC), "LOAD", fnt)
	DMText(INVX0 + Int(148*INVSC), INVY0 + Int(127*INVSC), ld$, fnt)

	; occhio premuto (come PressingEye nel 2D): finche' il mouse resta giu'
	; sull'icona dell'occhio (in g17 a ~(10..28, 13..31) px gioco), il box
	; di destra mostra titoli-mastery e attributi invece di FOOD/WATER
	eyeDown = 0
	If MouseDown(1) And shEyeOK Then
		emx = MouseX() : emy = MouseY()
		If emx >= INVX0 + Int(9*INVSC) And emx < INVX0 + Int(29*INVSC) Then
			If emy >= INVY0 + Int(12*INVSC) And emy < INVY0 + Int(32*INVSC) Then eyeDown = 1
		EndIf
	EndIf

	; campione provvisorio: pannello bottoni RESURRECT/RE-INCARNATE/CANCEL
	; (grafica 40 alla sua posizione vera nel viewport, wRectPos926)
	If eyeDown = 1 Then
		DrawEyePanel()
	Else If pcPot > 0 And pcDRC = 5 Then
		DrawImage invImg40, INVX0 + invG40X*INVSC, INVY0 + invG40Y*INVSC
	Else If pcPot = 0 And pcDRC = 0 Then
		; campione confermato: pannello FOOD/WATER (DisplayFoodWater,
		; Viewport.cpp:7240 — box g20 nello stesso rect di g40, label g30/g31,
		; icona veleno g32, barre orizzontali a x=113, food y=69, water y=92)
		If invImg20 <> 0 Then DrawImage invImg20, INVX0 + invG40X*INVSC, INVY0 + invG40Y*INVSC
		If invImg30 <> 0 Then DrawImage invImg30, INVX0 + fw30X*INVSC, INVY0 + fw30Y*INVSC
		If invImg31 <> 0 Then DrawImage invImg31, INVX0 + fw31X*INVSC, INVY0 + fw31Y*INVSC
		If shPoison <> 0 And invImg32 <> 0 Then DrawImage invImg32, INVX0 + fw32X*INVSC, INVY0 + fw32Y*INVSC
		DrawFoodWaterBar(shFood, 69, 145, 72, 0)     ; marrone (palette 5)
		DrawFoodWaterBar(shWater, 92, 0, 0, 255)     ; blu (palette 14)
	EndIf

	; name-entry di REINCARNATE in corso nel 2D: avviso (fase 3: griglia qui)
	If pcKbd = 2 Then
		Color 0, 0, 0
		Rect INVX0 + 20, INVY0 + 136*INVSC - 60, 224*INVSC - 40, 26, True
		Color 255, 230, 140
		Text INVX0 + 112*INVSC, INVY0 + 136*INVSC - 52, "Inserisci il nome nel gioco 2D...", True
	EndIf
End Function

; testo col font ORIGINALE DM: img = atlante colorato (frame = codice ASCII),
; avanzamento 6px gioco alla scala corrente. x,y = angolo alto-sinistro.
Function DMText(x, y, s$, img)
	If img = 0 Then Return
	For k = 1 To Len(s$)
		c = Asc(Mid$(s$, k, 1))
		If c > 32 And c < 128 Then DrawImage img, x + Int((k-1)*6*INVSC), y, c
	Next
End Function

; pannello dell'occhio (TAG019036 del 2D, Character.cpp:4602): sfondo g20
; nello stesso rect di g40, titoli-mastery a x=108 (baseline 58+7k -> top
; 53+7k, max 4), attributi a x=108 da baseline 86 passo 7; valore corrente
; a x=174 (rosso se < max, verde se >), "/max" a x=192, grigio.
Function DrawEyePanel()
	If invImg20 <> 0 Then DrawImage invImg20, INVX0 + invG40X*INVSC, INVY0 + invG40Y*INVSC
	ln = 0
	For i = 0 To 3
		If shEyeT$(i) <> "-" And shEyeT$(i) <> "" Then
			; top a 55 (non 53): il bordo superiore di g20 e' spesso 2 game px
			; per l'upscale 2.75 (interno da game-y 2), a 53 il titolo ci finiva
			; sopra. +2 lo porta dentro; con 4 titoli (55..76) resta sopra gli attributi (81).
			DMText(INVX0 + Int(108*INVSC), INVY0 + Int((55 + ln*7)*INVSC), shEyeT$(i), fntGrey)
			ln = ln + 1
		EndIf
	Next
	For i = 1 To 6
		gy = 81 + (i-1)*7
		DMText(INVX0 + Int(108*INVSC), INVY0 + Int(gy*INVSC), shAtN$(i), fntGrey)
		fnt = fntGrey
		If shAtC(i) < shAtM(i) Then fnt = fntRed
		If shAtC(i) > shAtM(i) Then fnt = fntGreen
		DMText(INVX0 + Int(174*INVSC), INVY0 + Int(gy*INVSC), RSet$(Str$(shAtC(i)),3), fnt)
		DMText(INVX0 + Int(192*INVSC), INVY0 + Int(gy*INVSC), "/" + RSet$(Str$(shAtM(i)),3), fntGrey)
	Next
End Function

; ritratto murale acceso/spento: lo specchio usato nel 2D resta sbiadito
; (PORTRAIT_InActive) — qui si scurisce il quadro (il full-bright EntityFX 1
; rende il moltiplicatore colore molto visibile)
Function SetPortraitActive(ord, act)
	If ord < 0 Or ord >= NPORT Then Return
	If portEnt(ord) = 0 Then Return
	If act = 0 Then
		EntityColor portEnt(ord), 70, 70, 70
	Else
		EntityColor portEnt(ord), 255, 255, 255
	EndIf
End Function

; risposta "PORTS <n>" gia' in brLine$: count + blocchi di 5 righe
; (fx, fy, dir, ord, act) — qui interessa solo ord+act per lo sbiadimento
Function ParsePorts()
	pcount = Int(brLine$(0))
	pk = 1
	For i = 1 To pcount
		SetPortraitActive(Int(brLine$(pk+3)), Int(brLine$(pk+4)))
		pk = pk + 5
	Next
End Function

; =========================================================================
; BARRA MAGIE (HUD-4) — replica dell'area magia del 2D nella colonna destra
; =========================================================================
; L'area 2D sta a x 224..319, y 42..73 (spazio schermo/mouse del gioco):
;   linguette caster  y 42..48 (la selezionata 42..49, larga 44, col nome) —
;                     DrawSpellControls, Character.cpp:4997
;   pannello g9       rect Word12 (riquadri rune, backspace, cast) —
;                     SelectMagicCaster, Magic.cpp:728
;   riga delle 6 rune dello stadio corrente: glifi font 96+6*stadio+i a
;                     (239+14*i, baseline 58) — DrawSpellMenuCharacters
;   incantation       glifi a (241+9*i, baseline 70), essa stessa bottone
;                     "cast" (codice 108) — DisplayMagicSelection
; Qui tutto e' mappato a scala MAGSC con origine (magOX, magOY).

Function MagX(x2d)
	Return magOX + Int((x2d - 224) * MAGSC)
End Function

Function MagY(y2d)
	Return magOY + Int((y2d - 42) * MAGSC)
End Function

; testo col font DM a scala 2 (stesso avanzamento 6px gioco di DMText)
Function DMText2(x, y, s$, img)
	If img = 0 Then Return
	For k = 1 To Len(s$)
		c = Asc(Mid$(s$, k, 1))
		If c > 32 And c < 128 Then DrawImage img, x + Int((k-1)*6*MAGSC), y, c
	Next
End Function

; x della linguetta caster dello slot i: 233+14*i, +33 se oltre la
; selezionata (che occupa 44px invece di 11) — layout di DrawSpellControls
Function MagTabX(i)
	tx = 233 + 14 * i
	If i > hcCaster Then tx = tx + 33
	Return tx
End Function

; riquadro col bordo di 1px gioco (coordinate 2D INCLUSIVE, col Color corrente)
Function MagBox(x1, x2, y1, y2)
	t = Int(MAGSC)
	w = Int((x2 - x1 + 1) * MAGSC)
	h = Int((y2 - y1 + 1) * MAGSC)
	Rect MagX(x1), MagY(y1), w, t, True
	Rect MagX(x1), MagY(y2), w, t, True
	Rect MagX(x1), MagY(y1), t, h, True
	Rect MagX(x2), MagY(y1), t, h, True
End Function

Function DrawMagicBar()
	If hudValid = 0 Then Return
	If hcCaster < 0 Or hcCaster > 3 Then Return   ; nessun caster: area vuota
	If hcAct(hcCaster) = 0 Then Return

	magOX = VIEW_W + (BAR_W - Int(96 * MAGSC)) / 2
	magOY = BARH + 8

	; riquadri come nel 2D live: un QUADRATO per runa (101-106), il riquadro
	; incantation (108) e il quadrato del backspace (107) — bordo 1px gioco
	; attorno ai rect dei bottoni (xMax/yMax esclusivi)
	Color 0, 218, 218
	For b = 0 To magN-1
		MagBox(magBX1(b) - 1, magBX2(b), magBY1(b) - 1, magBY2(b))
	Next

	; freccia backspace (piena, come nel 2D) dentro il bottone 107
	For b = 0 To magN-1
		If magCode(b) = 107 Then
			ax = magBX1(b) + 1 : ay = magBY1(b) + 1
			Rect MagX(ax+4), MagY(ay+2), Int(6*MAGSC), Int(4*MAGSC), True   ; asta
			Rect MagX(ax+3), MagY(ay+1), Int(1*MAGSC), Int(6*MAGSC), True   ; punta
			Rect MagX(ax+2), MagY(ay+2), Int(1*MAGSC), Int(4*MAGSC), True
			Rect MagX(ax+1), MagY(ay+3), Int(1*MAGSC), Int(2*MAGSC), True
		EndIf
	Next

	; linguette caster (solo slot del party; le non selezionate solo se vivo)
	Color 0, 218, 218
	For i = 0 To 3
		If hcAct(i) Then
			tx = MagTabX(i)
			If i = hcCaster Then
				Rect MagX(tx), MagY(42), Int(45*MAGSC), Int(8*MAGSC), True
				DMText2 MagX(tx+2), MagY(43), hcName$(i), fntBlack2
				Color 0, 218, 218
			Else If hcHP(i) > 0 Then
				Rect MagX(tx), MagY(42), Int(12*MAGSC), Int(7*MAGSC), True
			EndIf
		EndIf
	Next

	; le 6 rune dello stadio corrente (baseline 58 -> top 53)
	If spStage >= 0 And fntCyan2 <> 0 Then
		st = spStage And 3
		For i = 0 To 5
			DrawImage fntCyan2, MagX(239 + 14*i), MagY(53), 96 + 6*st + i
		Next
	EndIf

	; incantation in composizione (baseline 70 -> top 65)
	If fntCyan2 <> 0 Then
		For i = 0 To 3
			If spInc(i) >= 96 And spInc(i) <= 119 Then
				DrawImage fntCyan2, MagX(241 + 9*i), MagY(65), spInc(i)
			EndIf
		Next
	EndIf
End Function

; click nella barra magie: linguette -> "SPELL <slot>", riquadri rune/
; backspace/cast -> "SPELL <code>" (rect mouse-space di Buttons18060 dal
; layout; xMax/yMax ESCLUSIVI come in SearchButtonList, CSBCode.cpp:7597)
Function MagicUI()
	If gClick = 0 Or bridge = 0 Or hudValid = 0 Then Return
	If hcCaster < 0 Or hcCaster > 3 Then Return
	x2 = 224 + Int((MouseX() - magOX) / MAGSC)
	y2 = 42 + Int((MouseY() - magOY) / MAGSC)
	If x2 < 224 Or x2 > 319 Or y2 < 42 Or y2 > 76 Then Return
	If y2 <= 48 Then
		For i = 0 To 3
			If hcAct(i) Then
				tx = MagTabX(i)
				w = 11 : If i = hcCaster Then w = 44
				If x2 >= tx And x2 <= tx + w Then
					If i <> hcCaster Then WriteLine bridge, "SPELL " + i
					gClick = 0
					Return
				EndIf
			EndIf
		Next
	Else
		For b = 0 To magN-1
			If x2 >= magBX1(b) And x2 < magBX2(b) And y2 >= magBY1(b) And y2 < magBY2(b) Then
				WriteLine bridge, "SPELL " + magCode(b)
				gClick = 0
				Return
			EndIf
		Next
	EndIf
End Function

; barra orizzontale FOOD/WATER come DrawFoodWaterBar (Viewport.cpp:7223):
; lunghezza = clamp(v+1024, 0, 3071)/32 px gioco, alta 7, a x=113 nel
; viewport; ombra nera a (+2,+2); giallo se v<0, rosso se v<-512.
Function DrawFoodWaterBar(v, gy, cr, cg, cb)
	If v < 0 Then cr = 255 : cg = 255 : cb = 0
	If v < -512 Then cr = 255 : cg = 0 : cb = 0
	L = v + 1024
	If L < 0 Then L = 0
	If L > 3071 Then L = 3071
	L = L / 32
	If L <= 0 Then Return
	Color 0, 0, 0
	Rect INVX0 + Int(115*INVSC), INVY0 + Int((gy+2)*INVSC), Int(L*INVSC), Int(7*INVSC), True
	Color cr, cg, cb
	Rect INVX0 + Int(113*INVSC), INVY0 + Int(gy*INVSC), Int(L*INVSC), Int(7*INVSC), True
End Function

; click dentro il pannello: hit-test dei bottoni di Buttons18372. I rect sono
; in spazio MOUSE del 2D (schermo 320x200, viewport a y=33): la conversione a
; coordinate pannello e' (x, y-33) * INVSC + origine pannello.
Function RRCClick(mx, my)
	For i = 0 To rrcN-1
		bx1 = INVX0 + (rrcX1(i)          ) * INVSC
		bx2 = INVX0 + (rrcX2(i)          ) * INVSC
		by1 = INVY0 + (rrcY1(i) - VPOFFY) * INVSC
		by2 = INVY0 + (rrcY2(i) - VPOFFY) * INVSC
		If mx >= bx1 And mx < bx2 And my >= by1 And my < by2 Then
			If bridge <> 0 Then WriteLine bridge, "RRC " + rrcCode(i)
			Return
		EndIf
	Next
End Function

; =========================================================================
; DRAG-AND-DROP INVENTARIO (solo campione CONFERMATO, pcPot=0)
; Il motore 2D fa tutto lo swap objectInHand<->slot (HandleClothingClick): il
; 3D hit-testa i 30 slot possession e invia "GRAB <i>" (il bridge inietta un
; click sullo slot). Posizioni = quelle del disegno pannello (invIconX/Y(i+8));
; cella 18px come la cornice del backpack.
; =========================================================================
Function InvSlotClick(mx, my)
	If sheetValid = 0 Or bridge = 0 Then Return
	cw = Int(18*INVSC)
	For i = 0 To 29
		ix = invIconX(i+8) : iy = invIconY(i+8)
		sx = INVX0 + Int((ix-1)*INVSC) : sy = INVY0 + Int((iy-1)*INVSC)
		If mx >= sx And mx < sx+cw And my >= sy And my < sy+cw Then
			WriteLine bridge, "GRAB " + i
			needSheet = 1              ; rinfresca gli slot dopo lo swap
			Return
		EndIf
	Next
End Function

; True se il cursore e' sopra uno slot con un oggetto VERO (shReal>=0): la
; manina si mostra solo dove si puo' effettivamente prendere qualcosa.
Function InvHoverTakeable(mx, my)
	If sheetValid = 0 Then Return False
	cw = Int(18*INVSC)
	For i = 0 To 29
		If shReal(i) >= 0 Then
			ix = invIconX(i+8) : iy = invIconY(i+8)
			sx = INVX0 + Int((ix-1)*INVSC) : sy = INVY0 + Int((iy-1)*INVSC)
			If mx >= sx And mx < sx+cw And my >= sy And my < sy+cw Then Return True
		EndIf
	Next
	Return False
End Function

; (ri)costruisce l'icona dell'oggetto in mano SENZA lo sfondo grigio: DrawInvIcon
; usa DrawImageRect (copia RAW, include il grigio 72,72,72 dell'atlante); qui la
; copio in un'immagine col fondo grigio, poi MaskImage rende il grigio trasparente
; -> DrawImage (che rispetta la maschera) disegna solo l'oggetto. Ricostruita solo
; quando cambia l'oggetto in mano (non ogni frame).
Function BuildHandObjImg()
	handObjNI = hcHandNI
	If handObjImg <> 0 Then FreeImage handObjImg : handObjImg = 0
	If hcHandNI < 0 Then Return
	; atlante + cella come DrawInvIcon; carico il PNG per-cella gia' pre-tagliato
	; col fondo grigio reso TRASPARENTE (invgfx/icons/, generato da g4Xf.png).
	; DrawImage rispetta l'alpha -> al cursore si vede solo l'oggetto.
	a = -1
	For i = 0 To 6
		If hcHandNI >= invW612(i) Then a = i
	Next
	If a < 0 Then Return
	cl = hcHandNI - invW612(a)
	If cl < 0 Or cl > 31 Then Return
	handObjImg = LoadImage("invgfx/icons/icon_" + a + "_" + cl + ".png")
End Function

; cursore del pannello: oggetto in mano disegnato centrato sul mouse (senza il
; grigio dietro); altrimenti manina in hover su uno slot prendibile. Chiamata
; DOPO DrawInvPanel (sopra tutto).
Function DrawInvCursor()
	If pcPot > 0 Then Return           ; niente drag sui provvisori
	mx = MouseX() : my = MouseY()
	If hcHandNI >= 0 Then
		If hcHandNI <> handObjNI Then BuildHandObjImg()
		If handObjImg <> 0 Then
			cw = Int(16*INVSC)
			DrawImage handObjImg, mx - cw/2, my - cw/2   ; oggetto centrato, senza grigio
		EndIf
	Else If invHand <> 0 And InvHoverTakeable(mx, my) Then
		DrawImage invHand, mx - 6, my - 5             ; punta del dito ~ sul cursore
	EndIf
End Function

; =========================================================================
; BARRA CAMPIONI (HUD in alto, tutta larghezza — ANALISI_HUD_3D.md §1-2)
; Replica il box 2D 66x29 a passo 69 (DrawCharacterState): nome, 3 barre
; verticali HP/STAMINA/MANA (altezza ceil(25*cur/max), dal basso), le due
; mani con cornici/segnaposto (IconDisplay rel. (4,10) e (24,10)), scudi
; 37-39 in sovrapposizione, box morto g8; a inventario aperto il
; mini-ritratto ORIGINALE DM (88x80) copre nome e mani, restano le barre.
; =========================================================================
; pfc = facing corrente del party (le funzioni non vedono i locali del main!)
Function DrawHUD(pfc)
	Color 0, 0, 0
	Rect 0, 0, VIEW_W+BAR_W, BARH, True
	If hudValid = 0 Or invReady = 0 Then Return

	; quadrato marching-order in cima alla colonna destra (fedele al 2D):
	; cella del campione = (charPosition+4-partyFacing)&3, riempita col suo
	; colore; sopra, la figurina di facing da g28 (colonna relativa, celle 19px)
	; nel 2D sta a filo del bordo DESTRO e SUPERIORE dello schermo (rect a
	; y=1, x=281..319 su 320) e NON ha nessun fondo: le celle vuote restano
	; nere/invisibili, si vede solo la cella colorata di ogni campione
	If marchN = 4 Then
		mox = VIEW_W + BAR_W - Int(marchTW*INVSC) - 3
		moy = 3
		For i = 0 To 3
			If hcAct(i) Then
				mc = (hcPos(i) + 4 - pfc) And 3
				cix = hcCol(i)
				If cix >= 0 And cix <= 15 Then Color palR(cix), palG(cix), palB(cix) Else Color 0, 218, 0
				Rect mox + Int(marchX(mc)*INVSC), moy + Int(marchY(mc)*INVSC), Int(marchW(mc)*INVSC), Int(marchH(mc)*INVSC), True
				; figurina di facing: cella-PNG separata disegnata con
				; DrawImage (l'alpha lascia vedere il colore-cella sotto;
				; DrawImageRect e' una copia raw e lo coprirebbe)
				ac = (hcFacing(i) + 4 - pfc) And 3
				If invArrow(ac) <> 0 Then
					DrawImage invArrow(ac), mox + Int(marchX(mc)*INVSC), moy + Int(marchY(mc)*INVSC)
				EndIf
			EndIf
		Next
	EndIf

	For i = 0 To 3
		If hcAct(i) Then
			bx = Int(i * 69 * INVSC)

			If hcHP(i) <= 0 Then
				; campione morto: box grafica 8 (ritagliata alla larghezza box)
				If invImg8 <> 0 Then DrawImageRect invImg8, bx, HBOXY, 0, 0, HBOXW, HBOXH
				DMText(bx + Int(1*INVSC), HBOXY + 2, hcName$(i), fntGrey)
			Else
				; fondo grigio del box (colore 12 della palette DM)
				Color 72, 72, 72
				Rect bx, HBOXY, HBOXW, HBOXH, True

				; NB: gli And di Blitz NON corto-circuitano — l'indice va
				; validato PRIMA di usarlo come indice d'array (hcPort puo'
				; essere -1: hudPort(-1) leggerebbe fuori dai limiti)
				showMini = 0
				If pcSel = i+1 Then
					po = hcPort(i)
					If po >= 0 And po <= 23 Then
						If hudPort(po) <> 0 Then showMini = 1
					EndIf
				EndIf
				If showMini Then
					; inventario aperto: mini-ritratto al posto di nome e mani
					DrawImage hudPort(po), bx + Int(7*INVSC), HBOXY
				Else
					; targhetta del nome: rettangolo (0,0)-(42,6) grigio chiaro
					; (palette 1) col nome in font DM — ARANCIO (palette 9) se
					; il campione possiede il cursore-mano, grigio 13 se no
					Color 109, 109, 109
					Rect bx, HBOXY, Int(43*INVSC), Int(7*INVSC), True
					If hcHand = i Then
						DMText(bx + Int(1*INVSC), HBOXY + 2, hcName$(i), fntOrange)
					Else
						DMText(bx + Int(1*INVSC), HBOXY + 2, hcName$(i), fntGrey)
					EndIf
					; le due mani (slot 0/1) come nell'inventario, offset
					; relativi al box: (4,10) e (24,10) da IconDisplay[0..7]
					fd = Int(18*INVSC)
					For hn = 0 To 1
						hx = 4 + hn*20
						If hn = 0 Then hd = hcH0d(i) : hf = hcH0f(i) Else hd = hcH1d(i) : hf = hcH1f(i)
						fx = bx + Int((hx-1)*INVSC) : fy = HBOXY + Int(9*INVSC)
						If hf = 33 Then DrawImageRect invFrm33, fx, fy, 0, 0, fd, fd
						If hf = 34 Then DrawImageRect invFrm34, fx, fy, 0, 0, fd, fd
						If hf = 35 Then DrawImageRect invFrm35, fx, fy, 0, 0, fd, fd
						DrawInvIcon(hd, bx + Int(hx*INVSC), HBOXY + Int(10*INVSC))
					Next
				EndIf

				; 3 barre vitali: geometria REALE del 2D (misurata pixel per
				; pixel: x = 46+b*7, larghe 4, y 2..27, dal basso, senza
				; canale di fondo — la parte vuota resta il grigio del box);
				; STAMINA e' gia' proporzionale (cur/max), niente /10 qui
				For b = 0 To 2
					If b = 0 Then cv = hcHP(i)   : mv = hcMaxHP(i)
					If b = 1 Then cv = hcStam(i) : mv = hcMaxStam(i)
					If b = 2 Then cv = hcMana(i) : mv = hcMaxMana(i)
					hb = 0
					If mv > 0 Then
						hb = (25*cv + mv - 1) / mv       ; ceil, scala 0..25
						If hb > 25 Then hb = 25
					EndIf
					If hb > 0 Then
						Color 0, 218, 0
						Rect bx + Int((46 + b*7)*INVSC), HBOXY + Int((27-hb)*INVSC), Int(4*INVSC), Int(hb*INVSC), True
					EndIf
				Next

				; scudi attivi: punteggiature sovrapposte al box (colorkey ok)
				If hcFire > 0 And invImg38 <> 0 Then DrawImageRect invImg38, bx, HBOXY, 0, 0, HBOXW, HBOXH
				If hcSpell > 0 And invImg39 <> 0 Then DrawImageRect invImg39, bx, HBOXY, 0, 0, HBOXW, HBOXH
				If (hcParty > 0 Or hcShield(i) > 0) And invImg37 <> 0 Then DrawImageRect invImg37, bx, HBOXY, 0, 0, HBOXW, HBOXH
			EndIf
		EndIf
	Next
End Function

; --------------------------------------------------------- porta di legno 3D
; Anta in GEOMETRIA su un pivot (radice che scorre su): lastra texturizzata legno
; + 3 bande di ferro sporgenti + borchie (cubetti) color ferro chiaro su ogni banda.
; thinX=True -> varco est-ovest (anta sottile in X); altrimenti sottile in Z.
; cx,cz = centro cella porta; base Y = CELL/2. Ritorna il pivot.
Function BuildWoodDoor(cx#, cz#, thinX)
	piv = CreatePivot()
	PositionEntity piv, cx, CELL/2.0, cz

	slab = CreateCube()
	EntityParent slab, piv
	If thinX Then
		ScaleEntity slab, DOOR_T, DOOR_H, DOOR_W
	Else
		ScaleEntity slab, DOOR_W, DOOR_H, DOOR_T
	EndIf
	PositionEntity slab, 0, 0, 0                 ; LOCALE: centro del pivot
	EntityColor slab, 150, 110, 70
	If woodtex <> 0 Then EntityTexture slab, woodtex

	For bi = 0 To 2
		ly# = (bi-1) * (DOOR_H*0.62)            ; offset LOCALE in Y rispetto al pivot
		band = CreateCube()
		EntityParent band, piv
		If thinX Then
			ScaleEntity band, DOOR_T*1.25, DOOR_H*0.10, DOOR_W*1.01
		Else
			ScaleEntity band, DOOR_W*1.01, DOOR_H*0.10, DOOR_T*1.25
		EndIf
		PositionEntity band, 0, ly, 0
		EntityColor band, 60, 62, 70

		For si = -2 To 2
			stud = CreateCube()
			EntityParent stud, piv
			sd# = DOOR_T*0.55
			off# = si * (DOOR_W*0.40)
			If thinX Then
				ScaleEntity stud, DOOR_T*1.5, sd, sd
				PositionEntity stud, 0, ly, off
			Else
				ScaleEntity stud, sd, sd, DOOR_T*1.5
				PositionEntity stud, off, ly, 0
			EndIf
			EntityColor stud, 170, 172, 185
		Next
	Next

	Return piv
End Function

; --------------------------------------------------------- dispatcher per tipo
; Sceglie lo stile dell'anta dal doorType del ponte:
;   0 (e -1 sconosciuto) -> ferro a grata (buchi, si vede attraverso)
;   1                    -> legno
Function BuildDoor(cx#, cz#, thinX, dtype)
	If dtype = 1 Then
		Return BuildWoodDoor(cx, cz, thinX)
	Else
		Return BuildIronDoor(cx, cz, thinX)
	EndIf
End Function

; --------------------------------------------------------- porta di ferro (grata)
; Reticolo di barre di ferro (5 verticali x 5 orizzontali) con i VUOTI (buchi) tra
; cui si vede dietro, stile portcullis di Dungeon Master. Borchie agli incroci.
; Niente lastra piena: e' la grata stessa. thinX/cx/cz come BuildWoodDoor.
Function BuildIronDoor(cx#, cz#, thinX)
	piv = CreatePivot()
	PositionEntity piv, cx, CELL/2.0, cz

	bt# = 0.08                         ; mezza larghezza barra (piu' sottile)
	bd# = DOOR_T * 0.5                 ; mezza profondita' (dimezzata: meno spessa)
	gw# = DOOR_W * 0.75                ; mezza-larghezza grata (-3%, centrata)
	gh# = DOOR_H * 0.88                ; mezza-altezza grata (+5% ancora, centrata)

	; barre verticali (5: 2 bordi + 3 interne)
	For i = 0 To 4
		fx# = -gw + (2.0*gw) * i / 4.0
		bar = CreateCube()
		EntityParent bar, piv
		If thinX Then
			ScaleEntity bar, bd, gh, bt
			PositionEntity bar, 0, 0, fx
		Else
			ScaleEntity bar, bt, gh, bd
			PositionEntity bar, fx, 0, 0
		EndIf
		EntityColor bar, 235, 240, 250
		If irontex <> 0 Then EntityTexture bar, irontex
		EntityShininess bar, 0.12
	Next

	; barre orizzontali (5)
	For j = 0 To 4
		fy# = -gh + (2.0*gh) * j / 4.0
		bar = CreateCube()
		EntityParent bar, piv
		If thinX Then
			ScaleEntity bar, bd, bt, gw
		Else
			ScaleEntity bar, gw, bt, bd
		EndIf
		PositionEntity bar, 0, fy, 0
		EntityColor bar, 235, 240, 250
		If irontex <> 0 Then EntityTexture bar, irontex
		EntityShininess bar, 0.12
	Next

	; borchie/bulloni agli incroci (sporgono dalla grata)
	For i = 0 To 4
		fx# = -gw + (2.0*gw) * i / 4.0
		For j = 0 To 4
			fy# = -gh + (2.0*gh) * j / 4.0
			stud = CreateCube()
			EntityParent stud, piv
			sd# = bt*1.6
			If thinX Then
				ScaleEntity stud, bd*1.8, sd, sd
				PositionEntity stud, 0, fy, fx
			Else
				ScaleEntity stud, sd, sd, bd*1.8
				PositionEntity stud, fx, fy, 0
			EndIf
			EntityColor stud, 235, 240, 250
			If irontex <> 0 Then EntityTexture stud, irontex
			EntityShininess stud, 0.12

			; bullone: perno piu' sottile e piu' lungo che SPORGE dal cubetto
			; (spunta su entrambe le facce della grata)
			bolt = CreateCube()
			EntityParent bolt, piv
			bz# = sd*0.45
			If thinX Then
				ScaleEntity bolt, bd*2.3, bz, bz
				PositionEntity bolt, 0, fy, fx
			Else
				ScaleEntity bolt, bz, bz, bd*2.3
				PositionEntity bolt, fx, fy, 0
			EndIf
			EntityColor bolt, 205, 210, 220
			If irontex <> 0 Then EntityTexture bolt, irontex
			EntityShininess bolt, 0.12
		Next
	Next

	Return piv
End Function

; --------------------------------------------------------- stipiti (telaio porta)
; Telaio in pietra attorno al vano: due pali laterali + architrave in alto.
; STATICO (non sul pivot che scorre). Texture muro. thinX/cx/cz come le porte.
Function BuildDoorFrame(cx#, cz#, thinX)
	; due pali insieme ~28% della larghezza del vano (2*DOOR_W): 4*pw/(2*DOOR_W)
	pw# = 0.27                         ; mezza larghezza palo
	dp# = DOOR_T * 1.5                 ; mezza profondita' (meno profondo di prima)
	hY# = CELL/2.0

	; SOLO due pali laterali (niente architrave sopra): bordo esterno sul bordo del
	; vano, estesi verso l'interno -> interamente visibili nell'apertura.
	For s = -1 To 1 Step 2
		post = CreateCube()
		If thinX Then
			ScaleEntity post, dp, DOOR_H+0.12, pw
			PositionEntity post, cx, hY, cz + s*(CELL/2.0 - pw)
		Else
			ScaleEntity post, pw, DOOR_H+0.12, dp
			PositionEntity post, cx + s*(CELL/2.0 - pw), hY, cz
		EndIf
		EntityColor post, 210, 210, 210
		If jambtex <> 0 Then EntityTexture post, jambtex
	Next
End Function
