; dm3d.bb - Visualizzatore 3D di Dungeon Master (Fase 1, prototipo).
;
; CLIENT del "ponte": legge la MAPPA del livello (dm_level_0.txt, una volta) e
; lo STATO live (dm_state.txt, ogni frame) scritti dal motore CSBwin, e disegna
; il dungeon in 3D piazzando la camera dove si trova il party.
;
; Avviare DOPO che il gioco (CSBwin) e' entrato nel dungeon (cosi' i file esistono).
; ESC per uscire.

Graphics3D 640,480,0,2
SetBuffer BackBuffer()

Const CELL# = 4.0          ; unita' 3D per cella della griglia
Const EYE#  = 2.0          ; altezza occhi camera
Const SMOOTH# = 0.3        ; fattore di interpolazione camera (0..1): piu' alto = piu' rapido

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

; ---------------------------------------------------------------- mondo 3D
camera = CreateCamera()
CameraRange camera, 0.1, 2000
CameraClsColor camera, 0, 0, 0
; nebbia nera: piu' lontano = piu' scuro, fino al nero (look "buio" di DM)
CameraFogMode camera, 1
CameraFogColor camera, 0, 0, 0
CameraFogRange camera, CELL*1.0, CELL*4.5

light = CreateLight()
RotateEntity light, 50, 60, 0
; luce ambientale: senza, le superfici rivolte via dalla luce (es. la faccia
; inferiore del soffitto) restano nere. Cosi' tutto riceve una base di luce.
AmbientLight 120, 120, 120

; texture muri
walltex = LoadTexture("test.png")
; texture soffitto e pavimento (una piastrella per cella -> mapping di default).
; NB: niente piu' cuboni giganti per floor/ceiling (questo motore non li
;     renderizzava): pavimento e soffitto sono piastrelle costruite nel loop.
ceiltex  = LoadTexture("soffitto.png")
floortex = LoadTexture("pavimento2.png")

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
			If walltex <> 0 Then EntityTexture wall, walltex
		Else
			; cella calpestabile: piastrella pavimento (y=0) + soffitto (y=CELL)
			ft = CreateCube()
			ScaleEntity ft, CELL/2.0, 0.1, CELL/2.0
			PositionEntity ft, wx, 0.0, wz
			If floortex <> 0 Then
				EntityColor ft, 255, 255, 255
				EntityTexture ft, floortex
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

			; marker porta / scale sopra la piastrella
			If c$ = "4" Then
				dr = CreateCube()
				ScaleEntity dr, CELL/2.0, CELL/2.0, 0.2
				PositionEntity dr, wx, CELL/2.0, wz
				EntityColor dr, 110, 80, 40
			Else If c$ = "3" Then
				st = CreateCube()
				ScaleEntity st, CELL/3.0, 0.3, CELL/3.0
				PositionEntity st, wx, 0.6, wz
				EntityColor st, 200, 200, 60
			EndIf
		EndIf
	Next
Next

; ---------------------------------------------------------------- loop live
px = W/2 : py = H/2 : pf = 0      ; default finche' non leggiamo lo stato

; stato camera interpolato (movimento fluido)
camStarted = 0
cx# = 0.0 : cy# = EYE : cz# = 0.0 : cyaw# = 0.0

While Not KeyHit(1)
	; rilegge lo stato dinamico (puo' fallire se il file e' in scrittura: si tiene l'ultimo)
	sf = ReadFile("dm_state.txt")
	If sf <> 0 Then
		nx = Int(ReadLine$(sf))
		ny = Int(ReadLine$(sf))
		nf = Int(ReadLine$(sf))
		lv = Int(ReadLine$(sf))
		CloseFile sf
		If nx >= 0 And nx < W And ny >= 0 And ny < H Then
			px = nx : py = ny : pf = nf
		EndIf
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

	PositionEntity camera, cx, cy, cz
	RotateEntity camera, 0, cyaw, 0

	RenderWorld
	Color 255,255,255
	Text 0, 0,  "DM 3D  party (" + px + "," + py + ")  facing " + pf
	Text 0, 16, "ESC per uscire"
	Flip
Wend
End
