; ==========================================================================
; dm3d_world.bb - GEOMETRIA MONDO: PORTE 3D
; Incluso da dm3d.bb (inclusione TESTUALE Blitz3D: stesso scope globale).
; Contiene SOLO funzioni; globals/const/dim stanno nel main. Non modificare
; l'ordine di Include senza motivo.
; ==========================================================================

; --------------------------------------------------------- muro di roccia 3D
; Cubo-muro pieno (0..CELL) identico a quelli del loop di costruzione: stessa
; scala, texture diffuse (slot 0) + normal map (slot 1). Usato per l'anello di
; roccia attorno al perimetro della mappa (celle fuori griglia), altrimenti il
; party sul bordo che guarda fuori mappa vedrebbe il nero invece della roccia.
Function BuildRockWall(wx#, wz#)
	wall = CreateCube()
	ScaleEntity wall, CELL/2.0, CELL/2.0, CELL/2.0
	PositionEntity wall, wx, CELL/2.0, wz
	EntityColor wall, 255, 255, 255
	If walltex  <> 0 Then EntityTexture wall, walltex,  0, 0
	If walltexN <> 0 Then EntityTexture wall, walltexN, 0, 1
	Return wall
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
; Sceglie lo stile dell'anta da doorType + ornateness (entrambi dal ponte):
;   type 1                    -> legno
;   type 0, ornateness = 0    -> ferro a grata (buchi, si vede attraverso)
;   type 0, ornateness >= 1   -> porta piena metallica usurata
; In DM l'aspetto reale = base(doorType) + overlay(ornateness): due porte dello
; stesso doorType ma ornateness diverso NON si somigliano (una grata, una piena).
Function BuildDoor(cx#, cz#, thinX, dtype, orn)
	If dtype = 1 Then
		Return BuildWoodDoor(cx, cz, thinX)
	Else If orn >= 1 Then
		Return BuildMetalDoor(cx, cz, thinX)
	Else
		Return BuildIronDoor(cx, cz, thinX)
	EndIf
End Function

; --------------------------------------------------------- porta metallica piena
; Lastra piena (niente buchi) in metallo scuro usurato, con telaio/bande sporgenti
; piu' chiare, un montante centrale (due pannelli come nel 2D) e borchie sul bordo.
; Texture ferro (irontex) tinta scura. thinX/cx/cz come BuildWoodDoor.
Function BuildMetalDoor(cx#, cz#, thinX)
	piv = CreatePivot()
	PositionEntity piv, cx, CELL/2.0, cz

	; lastra piena: metallo scuro usurato
	slab = CreateCube()
	EntityParent slab, piv
	If thinX Then
		ScaleEntity slab, DOOR_T, DOOR_H, DOOR_W
	Else
		ScaleEntity slab, DOOR_W, DOOR_H, DOOR_T
	EndIf
	PositionEntity slab, 0, 0, 0
	EntityColor slab, 55, 58, 66                     ; nero-metallo
	If irontex <> 0 Then EntityTexture slab, irontex
	EntityShininess slab, 0.15

	; montante centrale verticale (divide in due pannelli, sporge dalla faccia)
	div = CreateCube()
	EntityParent div, piv
	If thinX Then
		ScaleEntity div, DOOR_T*1.25, DOOR_H*1.01, DOOR_W*0.14
	Else
		ScaleEntity div, DOOR_W*0.14, DOOR_H*1.01, DOOR_T*1.25
	EndIf
	PositionEntity div, 0, 0, 0
	EntityColor div, 90, 94, 104
	If irontex <> 0 Then EntityTexture div, irontex
	EntityShininess div, 0.2

	; telaio: bande orizzontali (alto/basso) sporgenti, metallo piu' chiaro
	For bi = -1 To 1 Step 2
		ly# = bi * (DOOR_H*0.88)
		band = CreateCube()
		EntityParent band, piv
		If thinX Then
			ScaleEntity band, DOOR_T*1.25, DOOR_H*0.12, DOOR_W*1.02
		Else
			ScaleEntity band, DOOR_W*1.02, DOOR_H*0.12, DOOR_T*1.25
		EndIf
		PositionEntity band, 0, ly, 0
		EntityColor band, 90, 94, 104
		If irontex <> 0 Then EntityTexture band, irontex
		EntityShininess band, 0.2
	Next

	Return piv
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
