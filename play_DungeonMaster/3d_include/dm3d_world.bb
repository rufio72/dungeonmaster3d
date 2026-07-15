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

; =========================================================================
; GANCIO DA MURO IN GEOMETRIA 3D (decorazione murale DM tipo 4)
; Nel 2D e' la grafica g130: placca di ottone inchiodata al muro con 4
; chiodi e un gancio nero appeso (appendi-abiti/carne). Qui lo costruiamo
; in vera geometria: pivot appoggiato alla faccia del muro con +Z locale
; che punta FUORI dal muro; i figli usano coordinate LOCALI (PositionEntity
; sui figli di un pivot e' locale di default).
;   wx,wy,wz = punto sulla superficie del muro; yaw = orientamento faccia
; =========================================================================
Function BuildWallHook(wx#, wy#, wz#, yaw#)
	piv = CreatePivot()
	PositionEntity piv, wx, wy, wz
	RotateEntity piv, 0, yaw, 0

	; cornice scura di fondo (leggermente piu' grande, quasi a filo muro)
	fr = CreateCube(piv)
	ScaleEntity fr, 0.30, 0.215, 0.014
	PositionEntity fr, 0, 0, 0.014
	EntityColor fr, 82, 46, 18

	; PLACCA di ottone
	pl = CreateCube(piv)
	ScaleEntity pl, 0.27, 0.185, 0.024
	PositionEntity pl, 0, 0, 0.030
	EntityColor pl, 202, 118, 44
	EntityShininess pl, 0.55

	; 4 CHIODI agli angoli (teste tonde grigio-ferro, appena sporgenti)
	For i = 0 To 3
		nx# = 0.20 : ny# = 0.125
		If (i And 1) Then nx = -nx
		If (i And 2) Then ny = -ny
		na = CreateSphere(8, piv)
		ScaleEntity na, 0.032, 0.032, 0.020
		PositionEntity na, nx, ny, 0.058
		EntityColor na, 168, 168, 172
		EntityShininess na, 0.4
	Next

	; GANCIO nero: borchia di attacco al centro della placca, gambo che
	; scende, poi la curva a J che risale IN FUORI (via dal muro)
	kb = CreateSphere(8, piv)
	ScaleEntity kb, 0.052, 0.052, 0.045
	PositionEntity kb, 0, 0.03, 0.070
	EntityColor kb, 28, 28, 32

	sh = CreateCylinder(10, True, piv)             ; gambo verticale
	ScaleEntity sh, 0.034, 0.225, 0.034
	PositionEntity sh, 0, 0.03 - 0.21, 0.085
	EntityColor sh, 28, 28, 32
	EntityShininess sh, 0.3

	; curva: due segmenti inclinati (piegano in fuori) + punta a sferetta
	b1 = CreateCylinder(10, True, piv)
	ScaleEntity b1, 0.032, 0.085, 0.032
	PositionEntity b1, 0, 0.03 - 0.425, 0.125
	RotateEntity b1, -48, 0, 0
	EntityColor b1, 28, 28, 32

	b2 = CreateCylinder(10, True, piv)
	ScaleEntity b2, 0.030, 0.080, 0.030
	PositionEntity b2, 0, 0.03 - 0.44, 0.215
	RotateEntity b2, -115, 0, 0
	EntityColor b2, 28, 28, 32

	tp = CreateSphere(8, piv)                      ; punta
	ScaleEntity tp, 0.034, 0.034, 0.034
	PositionEntity tp, 0, 0.03 - 0.40, 0.262
	EntityColor tp, 28, 28, 32
End Function

; =========================================================================
; ANELLO DA MURO IN GEOMETRIA 3D (decorazione murale DM tipo 6)
; Nel 2D e' la grafica g134: una FIBBIA di metallo inchiodata al muro che
; regge un grosso anello di ferro scuro appeso a un perno. Stesso schema del
; gancio: pivot sulla superficie del muro con +Z locale che punta FUORI dal
; muro, figli in coordinate LOCALI. L'anello e' un toro approssimato con 12
; segmenti-cilindro su un sotto-pivot, appena inclinato (la base sfiora il
; muro, la cima e' fuori sul perno).
;   wx,wy,wz = punto sulla superficie del muro; yaw = orientamento faccia
; =========================================================================
Function BuildWallRing(wx#, wy#, wz#, yaw#)
	piv = CreatePivot()
	PositionEntity piv, wx, wy, wz
	RotateEntity piv, 0, yaw, 0

	; FIBBIA a fettuccia: passa SOPRA il tubo dell'anello e ricade davanti
	; (l'anello sta infilato nell'asola, altrimenti non lo reggerebbe).
	; Tre pezzi: piastra inchiodata al muro, ponte sopra il tubo, linguetta
	; davanti all'anello chiusa da un ribattino.
	pl = CreateCube(piv)                           ; piastra sul muro
	ScaleEntity pl, 0.05, 0.07, 0.016
	PositionEntity pl, 0, 0.27, 0.018
	EntityColor pl, 74, 76, 82
	EntityShininess pl, 0.35

	na = CreateSphere(8, piv)                      ; un chiodo in alto, centrato
	ScaleEntity na, 0.024, 0.024, 0.016
	PositionEntity na, 0, 0.305, 0.038
	EntityColor na, 150, 150, 156
	EntityShininess na, 0.4

	pb2 = CreateCube(piv)                          ; ponte SOPRA il tubo
	ScaleEntity pb2, 0.05, 0.018, 0.062
	PositionEntity pb2, 0, 0.242, 0.078
	EntityColor pb2, 74, 76, 82
	EntityShininess pb2, 0.35

	pf2 = CreateCube(piv)                          ; linguetta DAVANTI all'anello:
	ScaleEntity pf2, 0.05, 0.095, 0.012           ; LUNGA, scende ben sotto il tubo
	PositionEntity pf2, 0, 0.15, 0.146
	EntityColor pf2, 74, 76, 82
	EntityShininess pf2, 0.35

	rv = CreateSphere(8, piv)                      ; ribattino in FONDO alla linguetta
	ScaleEntity rv, 0.022, 0.022, 0.014           ; (sul metallo, non sul cerchio)
	PositionEntity rv, 0, 0.09, 0.16
	EntityColor rv, 150, 150, 156
	EntityShininess rv, 0.4

	; ANELLO di ferro scuro appeso al perno
	rr# = 0.21                                     ; raggio dell'anello
	rp = CreatePivot(piv)
	PositionEntity rp, 0, 0.185 - rr, 0.068
	RotateEntity rp, 7, 0, 0                       ; cima in fuori, base verso il muro
	For k = 0 To 11
		a# = k * 30.0
		sg = CreateCylinder(8, True, rp)
		ScaleEntity sg, 0.036, rr*0.34, 0.036      ; mezzo-lato > rr*Sin(15): niente buchi
		PositionEntity sg, rr*Cos(a), rr*Sin(a), 0
		RotateEntity sg, 0, 0, a                   ; asse Y del cilindro = tangente
		EntityColor sg, 40, 41, 46
		EntityShininess sg, 0.25
	Next
End Function

; =========================================================================
; GRATA DI SCOLO IN GEOMETRIA 3D (decorazione murale DM tipo 34)
; Nel 2D e' la grafica g190: grata di legno per lo scolo dell'acqua alla
; BASE del muro (appoggiata al pavimento, non a mezza parete): cornice e
; stecche verticali di legno con aperture scure, architrave di pietra sopra.
; Stesso schema di gancio/anello: pivot sulla superficie del muro con +Z
; locale che punta FUORI, figli in coordinate LOCALI. wy = quota del
; CALPESTIO (base della grata), non la mezzeria.
; =========================================================================
Function BuildWallGrate(wx#, wy#, wz#, yaw#)
	piv = CreatePivot()
	PositionEntity piv, wx, wy, wz
	RotateEntity piv, 0, yaw, 0

	; fondo NERO (lo scarico dietro le stecche)
	bg = CreateCube(piv)
	ScaleEntity bg, 0.36, 0.27, 0.008
	PositionEntity bg, 0, 0.27, 0.004
	EntityColor bg, 8, 8, 8

	; architrave di pietra sopra la grata
	li = CreateCube(piv)
	ScaleEntity li, 0.40, 0.035, 0.035
	PositionEntity li, 0, 0.575, 0.020
	EntityColor li, 120, 120, 122
	If walltex <> 0 Then EntityTexture li, walltex, 0, 0

	; traversa di legno in alto
	tr = CreateCube(piv)
	ScaleEntity tr, 0.36, 0.045, 0.035
	PositionEntity tr, 0, 0.495, 0.028
	EntityColor tr, 150, 110, 70
	If woodtex <> 0 Then EntityTexture tr, woodtex

	; montanti laterali + 3 stecche centrali (4 aperture, come la grafica)
	For k = -2 To 2
		sx# = 0.042 : sz# = 0.030
		If Abs(k) = 2 Then sx = 0.050 : sz = 0.035   ; i due montanti esterni
		sl = CreateCube(piv)
		ScaleEntity sl, sx, 0.225, sz
		PositionEntity sl, k*0.155, 0.225, 0.026
		EntityColor sl, 150, 110, 70
		If woodtex <> 0 Then EntityTexture sl, woodtex
	Next
End Function

; =========================================================================
; OGGETTI A TERRA (dinamici: si raccolgono e si posano)
; Il payload FLOOR/FLOORP e' gia' in brLine$: count, poi 6 righe per oggetto
; (x / y / pos / dbType / objNI / nome). Ricostruisce DA ZERO i decal piatti:
; texture = icona vera dell'oggetto (floorobj/ni<NI>.png, affettata dagli
; atlanti g42..48 da gen_floorobj.py; ni<NI>_custom.png ha la precedenza).
; pos = quadrante del tile (0=NW 1=NE 2=SE 3=SW); nel mondo 3D nord = -Z e
; ovest = +X (mondo riflesso su X, come la camera). Oggetti che condividono
; cella+quadrante si impilano con un piccolo rialzo (niente z-fight).
; Il bridge manda solo il raggio ~8 celle attorno al party: piu' del buio
; della nebbia, quindi ricostruire tutto a ogni refresh non si nota mai.
; =========================================================================
Function BuildFloorObjs(w, h)
	For i = 0 To floN-1
		If floEnt(i) <> 0 Then FreeEntity floEnt(i)
		floEnt(i) = 0
	Next
	floN = 0
	n = Int(brLine$(0))
	k = 1
	For i = 1 To n
		fx = Int(brLine$(k))
		fy = Int(brLine$(k+1))
		fp = Int(brLine$(k+2)) And 3
		ni = Int(brLine$(k+4))          ; k+3 = dbType, k+5 = nome (non servono)
		k = k + 6
		ok = (floN < FLOBJ_MAX And fx >= 0 And fx < w And fy >= 0 And fy < h And ni >= 0 And ni <= 255)
		; le celle-MURO contengono oggetti "invisibili" (es. l'equipaggiamento
		; dei campioni vive dentro la cella dello specchio): saltate, come nel 2D
		If ok Then
			If Mid$(grid$(fy), fx+1, 1) = "0" Then ok = 0
		EndIf
		If ok Then
			If floTex(ni) = 0 Then
				; asset EDITABILE: la versione ridipinta a mano vince sempre
				cust$ = "floorobj/ni" + ni + "_custom.png"
				If FileType(cust$) = 1 Then
					floTex(ni) = LoadTexture(cust$, 2)              ; flag 2 = alpha
				Else
					floTex(ni) = LoadTexture("floorobj/ni" + ni + ".png", 2)
				EndIf
				; PNG top-left vs campionamento GL bottom-left (flip delle targhe)
				If floTex(ni) <> 0 Then ScaleTexture floTex(ni), 1, -1
			EndIf
			; quadrante -> offset dal centro cella (nord = -Z, ovest = +X)
			q# = CELL*0.25
			dxo# = q : If fp = 1 Or fp = 2 Then dxo = -q
			dzo# = q : If fp = 0 Or fp = 1 Then dzo = -q
			; oggetti sovrapposti nello stesso quadrante: rialzo progressivo
			key = (fy*64 + fx)*4 + fp
			lift = 0
			For j = 0 To floN-1
				If floKey(j) = key Then lift = lift + 1
			Next
			e = CreateCube()
			ScaleEntity e, FLOBJ_HW, 0.01, FLOBJ_HW
			; base a ~0.19: sopra l'erba (top 0.15) e sopra la lastra delle
			; pedane (top ~0.179), cosi' un oggetto posato su una pedana si vede
			PositionEntity e, (w-1-fx)*CELL + dxo, 0.20 + lift*0.025, fy*CELL + dzo
			EntityColor e, 255, 255, 255
			If floTex(ni) <> 0 Then EntityTexture e, floTex(ni)
			floKey(floN) = key
			floEnt(floN) = e
			floN = floN + 1
		EndIf
	Next
End Function

; ------------------------------------------------------------------- scale 3D
; Scala in GEOMETRIA dentro la cella: gradini a tutta larghezza che scendono
; (up=0) sotto il pavimento o salgono (up=1) verso il soffitto, sviluppandosi
; lungo (dx,dz) = direzione unitaria mondo VIA dall'ingresso. La cella arriva
; qui SENZA la piastrella bucata dal vano (pavimento se discesa, soffitto se
; salita: il loop di costruzione la salta). Il vano sfuma nel buio: gradini
; via via piu' scuri + pannelli di pietra scura che chiudono fianchi e fondo,
; cosi' non si vede mai il void. cx,cz = centro cella. STAIR_N/STAIR_DROP nel main.
Function BuildStairs(cx#, cz#, dx#, dz#, up)
	sd# = CELL / Float(STAIR_N)          ; pedata (profondita' di un gradino)
	sh# = STAIR_DROP / Float(STAIR_N)    ; alzata di un gradino
	base# = 0.1                          ; quota del calpestio (top piastrella)

	For i = 0 To STAIR_N-1
		u# = -CELL/2.0 + (i+0.5)*sd      ; centro del gradino lungo lo sviluppo
		If up = 1 Then
			top# = base + (i+1)*sh
			bot# = 0.0
		Else
			top# = base - (i+1)*sh
			bot# = base - STAIR_DROP - 0.5
		EndIf
		st = CreateCube()
		If dx <> 0 Then
			ScaleEntity st, sd/2.0, (top-bot)/2.0, CELL/2.0
		Else
			ScaleEntity st, CELL/2.0, (top-bot)/2.0, sd/2.0
		EndIf
		PositionEntity st, cx + dx*u, (top+bot)/2.0, cz + dz*u
		; scendendo (o salendo) nel vano la pietra sfuma verso il buio
		lum = 235 - i*28
		EntityColor st, lum, lum, lum
		If floortex <> 0 Then
			EntityTexture st, floortex, 0, 0                        ; diffuse slot 0
			If floortexN <> 0 Then EntityTexture st, floortexN, 0, 1 ; normal map slot 1
		EndIf
	Next

	; pannelli di chiusura del vano, OLTRE la quota della cella: sotto il
	; pavimento per la discesa, sopra il soffitto per la salita. Due fianchi
	; (paralleli allo sviluppo) + un fondo sul lato opposto all'ingresso.
	If up = 1 Then
		pb# = CELL : pt# = CELL + STAIR_DROP + 0.5
	Else
		pb# = base - STAIR_DROP - 0.5 : pt# = base
	EndIf
	For s = -1 To 1 Step 2
		fl = CreateCube()
		If dx <> 0 Then
			ScaleEntity fl, CELL/2.0, (pt-pb)/2.0, 0.15
			PositionEntity fl, cx, (pt+pb)/2.0, cz + s*(CELL/2.0)
		Else
			ScaleEntity fl, 0.15, (pt-pb)/2.0, CELL/2.0
			PositionEntity fl, cx + s*(CELL/2.0), (pt+pb)/2.0, cz
		EndIf
		EntityColor fl, 80, 80, 80
		If walltex  <> 0 Then EntityTexture fl, walltex,  0, 0
		If walltexN <> 0 Then EntityTexture fl, walltexN, 0, 1
	Next
	bk = CreateCube()
	If dx <> 0 Then
		ScaleEntity bk, 0.15, (pt-pb)/2.0, CELL/2.0
		PositionEntity bk, cx + dx*(CELL/2.0), (pt+pb)/2.0, cz
	Else
		ScaleEntity bk, CELL/2.0, (pt-pb)/2.0, 0.15
		PositionEntity bk, cx, (pt+pb)/2.0, cz + dz*(CELL/2.0)
	EndIf
	EntityColor bk, 80, 80, 80
	If walltex  <> 0 Then EntityTexture bk, walltex,  0, 0
	If walltexN <> 0 Then EntityTexture bk, walltexN, 0, 1

	; POGGIAMANO: corrimano in pietra sui due fianchi, inclinati come la
	; rampa, con un paletto di sostegno per gradino. AlignToVector allinea
	; l'asse X locale della trave allo sviluppo (niente giochi di segni).
	If up = 1 Then vy# = STAIR_DROP Else vy# = -STAIR_DROP
	railh# = 1.05                        ; altezza corrimano sopra il naso dei gradini
	rl# = Sqr(CELL*CELL + STAIR_DROP*STAIR_DROP)/2.0 + 0.1   ; mezza lunghezza trave
	For s = -1 To 1 Step 2
		If dx <> 0 Then
			lox# = 0 : loz# = s*(CELL/2.0 - 0.24)
		Else
			lox = s*(CELL/2.0 - 0.24) : loz = 0
		EndIf
		; manici in LEGNO (stesso legno delle ante delle porte)
		hr = CreateCube()
		ScaleEntity hr, rl, 0.055, 0.075
		PositionEntity hr, cx + lox, base + vy/2.0 + railh, cz + loz
		AlignToVector hr, dx*CELL, vy, dz*CELL, 1
		EntityColor hr, 150, 110, 70
		If woodtex <> 0 Then EntityTexture hr, woodtex

		For i = 0 To STAIR_N-1
			u# = -CELL/2.0 + (i+0.5)*sd
			If up = 1 Then ty# = base + (i+1)*sh Else ty = base - (i+1)*sh
			ry# = base + railh + vy*(u + CELL/2.0)/CELL   ; quota del corrimano sopra u
			po = CreateCylinder(8, True)
			ScaleEntity po, 0.042, (ry-ty)/2.0, 0.042
			PositionEntity po, cx + dx*u + lox, (ry+ty)/2.0, cz + dz*u + loz
			EntityColor po, 135, 98, 62
			If woodtex <> 0 Then EntityTexture po, woodtex
		Next
	Next
End Function
