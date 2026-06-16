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
CameraClsColor camera, 10, 10, 16

light = CreateLight()
RotateEntity light, 50, 60, 0

; texture muri: ritaglio dal gioco 2D
walltex = LoadTexture("dm_game_wall_final.png")

; pavimento (un cubo appiattito che copre tutto il livello)
floor = CreateCube()
ScaleEntity floor, W*CELL/2.0, 0.2, H*CELL/2.0
PositionEntity floor, (W-1)*CELL/2.0, -0.2, (H-1)*CELL/2.0
EntityColor floor, 70, 60, 50

; muri: un cubo per ogni cella di tipo 0 (wall)
For y = 0 To H-1
	row$ = grid$(y)
	For x = 0 To W-1
		c$ = Mid$(row$, x+1, 1)
		If c$ = "0" Then
			wall = CreateCube()
			ScaleEntity wall, CELL/2.0, CELL/2.0, CELL/2.0
			PositionEntity wall, (W-1-x)*CELL, CELL/2.0, y*CELL
			EntityColor wall, 255, 255, 255
			If walltex <> 0 Then EntityTexture wall, walltex
		Else If c$ = "4" Then          ; porta
			dr = CreateCube()
			ScaleEntity dr, CELL/2.0, CELL/2.0, 0.2
			PositionEntity dr, (W-1-x)*CELL, CELL/2.0, y*CELL
			EntityColor dr, 110, 80, 40
		Else If c$ = "3" Then          ; scale (marker)
			st = CreateCube()
			ScaleEntity st, CELL/3.0, 0.3, CELL/3.0
			PositionEntity st, (W-1-x)*CELL, 0.3, y*CELL
			EntityColor st, 200, 200, 60
		EndIf
	Next
Next

; ---------------------------------------------------------------- loop live
px = W/2 : py = H/2 : pf = 0      ; default finche' non leggiamo lo stato

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

	; Blitz3D e' left-handed: per non avere la scena specchiata sx/dx, il mondo
	; e' riflesso su X -> worldX = (W-1-gridX)*CELL (muri, porte, scale e camera).
	; Con questa riflessione lo yaw corretto e' (2-pf)*90 (verificato coi marker
	; N/E/S/W): front/back e left/right combaciano col gioco 2D.
	PositionEntity camera, (W-1-px)*CELL, EYE, py*CELL
	yaw# = (2 - pf) * 90
	RotateEntity camera, 0, yaw, 0

	RenderWorld
	Color 255,255,255
	Text 0, 0,  "DM 3D  party (" + px + "," + py + ")  facing " + pf
	Text 0, 16, "ESC per uscire"
	Flip
Wend
End
