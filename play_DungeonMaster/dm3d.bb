; dm3d.bb - Visualizzatore 3D di Dungeon Master (Fase 1, prototipo).
;
; CLIENT del "ponte": legge la MAPPA del livello (dm_level_0.txt, una volta) e
; lo STATO live (dm_state.txt, ogni frame) scritti dal motore CSBwin, e disegna
; il dungeon in 3D piazzando la camera dove si trova il party.
;
; Avviare DOPO che il gioco (CSBwin) e' entrato nel dungeon (cosi' i file esistono).
; ESC per uscire.

; Finestra: 3D pieno a sinistra (640x480) + barra nera azioni a destra (200px).
Const VIEW_W = 640         ; larghezza viewport 3D
Const BAR_W  = 200         ; larghezza barra azioni (un po' piu' larga dei tasti)
Graphics3D VIEW_W+BAR_W, 480, 0, 2
SetBuffer BackBuffer()

Const CELL# = 4.0          ; unita' 3D per cella della griglia
Const EYE#  = 2.0          ; altezza occhi camera
Const SMOOTH# = 0.15       ; interpolazione camera (piu basso = piu fluido tra gli step)

Const NPORT = 24           ; ritratti dei campioni: portraits/00.png .. portraits/23.png
Const PORT_W# = CELL*0.55  ; larghezza/altezza del quadro appeso al muro
Const PORT_T# = 0.10       ; spessore della tavola (sottile)

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
Global hovImg = 0          ; highlight semi-trasparente per la cella sotto il mouse

; ----------------------------------------- attende la mappa (entra nel dungeon)
W = 0 : H = 0
While W <= 0 Or H <= 0
	If KeyHit(1) Then End
	mapf = ReadFile("dm_level_0.txt")
	If mapf <> 0 Then
		W = Int(ReadLine$(mapf))
		H = Int(ReadLine$(mapf))
		If W > 0 And W <= 64 And H > 0 And H <= 64 Then
			Dim grid$(H)
			For y = 0 To H-1
				grid$(y) = ReadLine$(mapf)
			Next
		Else
			W = 0 : H = 0      ; file incompleto, riprova
		EndIf
		CloseFile mapf
	EndIf
	If W <= 0 Then
		Cls
		Color 200,200,200
		Text 20, 200, "In attesa... entra nel dungeon nel gioco 2D (CSBwin)"
		Text 20, 220, "(ESC per uscire)"
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
CameraViewport camera, 0, 0, VIEW_W, 480     ; 3D solo a sinistra; a destra la barra
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

; tipo di ogni porta (statico): letto UNA volta dal ponte (dm_state.txt) per
; scegliere lo stile in costruzione. -1 = sconosciuto (default: ferro).
Dim doorTypeAt(W*H)
For i = 0 To W*H-1 : doorTypeAt(i) = -1 : Next
tf = ReadFile("dm_state.txt")
If tf <> 0 Then
	tj$ = ReadLine$(tf) : tj$ = ReadLine$(tf)        ; px, py
	tj$ = ReadLine$(tf) : tj$ = ReadLine$(tf)        ; facing, level
	tmc = Int(ReadLine$(tf))                         ; n. mostri
	For m = 1 To tmc
		tj$ = ReadLine$(tf) : tj$ = ReadLine$(tf) : tj$ = ReadLine$(tf)
	Next
	tdc = Int(ReadLine$(tf))                         ; n. porte
	For m = 1 To tdc
		ax = Int(ReadLine$(tf))
		ay = Int(ReadLine$(tf))
		az = Int(ReadLine$(tf))                      ; state (ignorato qui)
		aty = Int(ReadLine$(tf))                     ; type
		If ax >= 0 And ax < W And ay >= 0 And ay < H Then doorTypeAt(ay*W+ax) = aty
	Next
	CloseFile tf
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

; Posizioni REALI dal ponte (dm_portraits_0.txt): un ritratto per riga-blocco
;   fx, fy (cella calpestabile)  +  dir muro (0=ovest 1=est 2=nord 3=sud)  +  ord
; Coordinate griglia identiche alla mappa, quindi stessa proiezione della camera.
pf = ReadFile("dm_portraits_0.txt")
If pf <> 0 Then
	pcount = Int(ReadLine$(pf))
	For i = 1 To pcount
		fx  = Int(ReadLine$(pf))
		fy  = Int(ReadLine$(pf))
		dir = Int(ReadLine$(pf))
		ord = Int(ReadLine$(pf))
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
		EndIf
	Next
	CloseFile pf
EndIf

; ---------------------------------------------------------------- loop live
px = W/2 : py = H/2 : pf = 0      ; default finche' non leggiamo lo stato

; stato camera interpolato (movimento fluido)
camStarted = 0
cx# = 0.0 : cy# = EYE : cz# = 0.0 : cyaw# = 0.0

; --- stato torcia (fiamma): a SCATTI, non oscillazione continua. Per lo piu' arancio
; steady; OGNI TANTO una breve fiammata che aumenta luminosita' E vira al giallo limone.
torchNext = 0       ; ms del prossimo cambio di stato
torchFlare# = 0.0   ; 0 = baseline arancio .. 1 = fiammata gialla intensa
torchFlaring = 0    ; 0 = baseline, 1 = sta fiammeggiando

While Not KeyHit(1)
	; rilegge lo stato dinamico (puo' fallire se il file e' in scrittura: si tiene l'ultimo)
	sf = ReadFile("dm_state.txt")
	If sf <> 0 Then
		nx = Int(ReadLine$(sf))
		ny = Int(ReadLine$(sf))
		nf = Int(ReadLine$(sf))
		lv = Int(ReadLine$(sf))
		If nx >= 0 And nx < W And ny >= 0 And ny < H Then
			px = nx : py = ny : pf = nf
		EndIf

		; sezione mostri: la saltiamo (3 valori per mostro), poi le porte.
		mcount = Int(ReadLine$(sf))
		For m = 1 To mcount
			junk = ReadLine$(sf) : junk = ReadLine$(sf) : junk = ReadLine$(sf)
		Next

		; sezione porte: x, y, state (0=aperta..4=chiusa, 5=foro), type (0/1).
		dcount = Int(ReadLine$(sf))
		For m = 1 To dcount
			gx  = Int(ReadLine$(sf))
			gy  = Int(ReadLine$(sf))
			gst = Int(ReadLine$(sf))
			gty = Int(ReadLine$(sf))
			If gx >= 0 And gx < W And gy >= 0 And gy < H Then
				idx = gy*W + gx
				dr = doorEnt(idx)
				If dr <> 0 Then
					; apertura bersaglio: 4=chiusa->0, 0=aperta->1; 5 (foro)=tutta aperta
					tOpen# = (4.0 - gst) / 4.0
					If gst >= 5 Then tOpen = 1.0
					If tOpen < 0.0 Then tOpen = 0.0
					If tOpen > 1.0 Then tOpen = 1.0
					; scorrimento fluido verso il bersaglio (portcullis: sale)
					doorOpen#(idx) = doorOpen#(idx) + (tOpen - doorOpen#(idx)) * DOOR_SMOOTH
					; muovo il PIVOT della porta: i figli (lastra/bande/borchie) seguono
					wxd# = (W-1-gx)*CELL
					wzd# = gy*CELL
					PositionEntity dr, wxd, doorBaseY#(idx) + doorOpen#(idx)*CELL, wzd
				EndIf
			EndIf
		Next

		CloseFile sf
	EndIf

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
	Rect VIEW_W, 0, BAR_W, 480, True

	Color 255,255,255
	Text 0, 0,  "DM 3D  party (" + px + "," + py + ")  facing " + pf
	Text 0, 16, "ESC per uscire"

	MoveUI()    ; frecce di movimento cliccabili (scrivono dm_command.txt -> CSBwin)

	shotN = shotN + 1
	If SHOT And shotN = 45 Then
		SaveBuffer BackBuffer(), "dm3d_shot.bmp"
		End
	EndIf

	Flip
Wend
End

; =========================================================================
; PAD DI MOVIMENTO (canale 3D -> 2D)
; Frecce cliccabili in basso a destra. Al click scrivono un codice in
; "dm_command.txt"; CSBwin lo legge ogni tick (Bridge_Tick) e lo inietta
; nella coda mouse come azione gia' risolta -> il party si muove anche nel 2D.
;   1=gira sx  2=gira dx  3=avanti  4=strafe dx  5=indietro  6=strafe sx
; =========================================================================
; Pad di movimento: disegna la grafica VERA di DM (mvbuttons.bmp, grafica 13
; estratta da CSBwin) in basso a destra, e fa l'hit-test della griglia 3x2.
; Layout (come DM):  [gira-sx][avanti][gira-dx] / [strafe-sx][indietro][strafe-dx]
Function MoveUI()
	If mvImg = 0 Then mvImg = LoadImage("mvbuttons.png")   ; PNG con alpha: nero gia' trasparente, 2x nearest (netto)
	If hovImg = 0 Then hovImg = LoadImage("hover.png")     ; tinta bianca semi-trasparente per l'hover
	If mvImg = 0 Then Return            ; immagine non trovata: nessun pad

	iw = ImageWidth(mvImg) : ih = ImageHeight(mvImg)
	px = VIEW_W + (BAR_W - iw)/2      ; centrato nella barra destra
	py = 480 - ih - 14               ; in fondo alla barra (sopra: spazio per la scheda PG)
	cw = iw/3 : ch = ih/2            ; dimensione cella (per hover e hit-test)

	mx = MouseX() : my = MouseY() : clk = MouseHit(1)
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

; scrive il comando di movimento per CSBwin
Function SendCmd(code)
	f = WriteFile("dm_command.txt")
	If f<>0 Then
		WriteLine f, Str(code)
		CloseFile f
	EndIf
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
