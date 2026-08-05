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

	; anta LISCIA: niente bande di ferro ne' borchie (nel 2D la porta di legno
	; e' solo assi verticali di legno; bande e cubetti leggevano come corpi
	; estranei — richiesta utente). Il dettaglio lo fa la texture del legno.

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

; ------------------------------------------------- pomello della porta (switch)
; Le porte con doorSwitch (bit 6 del record DB0) hanno il POMELLO marrone che
; le apre/chiude. Nel 2D (DrawDoorSwitch) sta SEMPRE a destra del viewport:
; in mondo quindi sta su stipiti OPPOSTI a seconda del lato di approccio
; (diagonale). Per ogni lato: piastra scura + pomello marrone sulla faccia del
; palo rivolta a chi arriva. STATICI (non sul pivot che scorre) e PICKABILI:
; EntityName "doorbtn", il main loop su click manda "DOORBTN" al bridge, che
; inietta il click 2D sulla regione del pomello (guardie e TT_DOOR nel motore).
; s = lato di approccio lungo l'asse del varco; il palo "a destra" guardando
; la porta da quel lato risulta dal prodotto vettore avanti x su.
Function BuildDoorButtons(cx#, cz#, thinX)
	pw# = 0.27                          ; mezza larghezza palo (come BuildDoorFrame)
	dp# = DOOR_T * 1.5                  ; mezza profondita' palo
	po# = CELL/2.0 - pw                 ; centro palo dal centro cella
	by# = 2.2                           ; quota pomello (poco sopra la mezzeria)
	For s = -1 To 1 Step 2
		If thinX Then
			; varco est-ovest: Blitz3D e' LEFT-handed (mondo riflesso su X),
			; quindi da +X guardando -X la destra a schermo e' +Z (e viceversa)
			px1# = cx + s*(dp + 0.030) : pz1# = cz + s*po      ; piastra
			px2# = cx + s*(dp + 0.075) : pz2# = pz1           ; pomello
		Else
			; varco nord-sud: da +Z guardando -Z la destra a schermo e' -X
			px1# = cx - s*po : pz1# = cz + s*(dp + 0.030)
			px2# = px1       : pz2# = cz + s*(dp + 0.075)
		EndIf
		plt = CreateCube()                                 ; piastra scura di fondo
		If thinX Then
			ScaleEntity plt, 0.030, 0.14, 0.11
		Else
			ScaleEntity plt, 0.11, 0.14, 0.030
		EndIf
		PositionEntity plt, px1, by, pz1
		EntityColor plt, 58, 36, 16
		EntityPickMode plt, 3
		NameEntity plt, "doorbtn"
		kb = CreateCube()                                  ; pomello marrone
		If thinX Then
			ScaleEntity kb, 0.065, 0.075, 0.062
		Else
			ScaleEntity kb, 0.062, 0.075, 0.065
		EndIf
		PositionEntity kb, px2, by, pz2
		EntityColor kb, 148, 82, 30
		EntityShininess kb, 0.1
		EntityPickMode kb, 3
		NameEntity kb, "doorbtn"
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

; anello/arco di ferro generico: nseg segmenti-cilindro sul piano XY del
; sotto-pivot restituito (aspan=360 -> anello chiuso; meno -> arco aperto,
; es. i ceppi). Chi chiama posiziona/ruota il pivot e vi appende dettagli.
Function BuildIronArc(parent, rad#, tube#, a0#, aspan#, nseg, cr, cg, cb, shin#)
	tp = CreatePivot(parent)
	For k = 0 To nseg-1
		a# = a0 + aspan * (k + 0.5) / nseg
		; mezzo-segmento = mezza corda con margine: niente buchi tra i pezzi
		hl# = rad * Sin(aspan / nseg / 2.0) * 1.25 + tube * 0.4
		sg = CreateCylinder(8, True, tp)
		ScaleEntity sg, tube, hl, tube
		PositionEntity sg, rad*Cos(a), rad*Sin(a), 0
		RotateEntity sg, 0, 0, a                   ; asse Y del cilindro = tangente
		EntityColor sg, cr, cg, cb
		EntityShininess sg, shin
	Next
	Return tp
End Function

; =========================================================================
; MANETTE DEL PRIGIONIERO IN GEOMETRIA 3D (dispositivo murale DM tipo 36)
; Nel 2D e' la grafica g194: una TAVOLA di legno inchiodata al muro con due
; chiodi tondi; ai bordi due STAFFE d'acciaio da cui pendono CATENE che
; finiscono in CEPPI aperti di ferro scuro arrugginito. Stesso schema di
; gancio/anello/grata: pivot sulla superficie del muro con +Z locale che
; punta FUORI dal muro, figli in coordinate LOCALI, wy = mezzeria (EYE).
; Catena = anelli pieni alternati (frontale/di taglio, come una catena vera);
; ceppo = arco aperto in basso (BuildIronArc) con le punte a sferetta.
; =========================================================================
Function BuildWallManacles(wx#, wy#, wz#, yaw#)
	piv = CreatePivot()
	PositionEntity piv, wx, wy, wz
	RotateEntity piv, 0, yaw, 0
	; tutta la decorazione e' +40% (i figli ereditano la scala del pivot)
	ScaleEntity piv, 1.4, 1.4, 1.4

	; TAVOLA di legno orizzontale (in alto: le catene pendono sotto)
	pl = CreateCube(piv)
	ScaleEntity pl, 1.00, 0.155, 0.045
	PositionEntity pl, 0, 0.55, 0.055
	EntityColor pl, 88, 44, 14

	; 2 CHIODI tondi sulla faccia della tavola (tra le staffe, come nel 2D)
	For i = 0 To 1
		nx# = 0.38 : If i = 1 Then nx = -0.38
		na = CreateSphere(8, piv)
		ScaleEntity na, 0.030, 0.030, 0.018
		PositionEntity na, nx, 0.55, 0.104
		EntityColor na, 168, 168, 172
		EntityShininess na, 0.4
	Next

	; per ciascun lato: staffa + ribattino + catena + ceppo
	For sd = 0 To 1
		s# = 1.0 : If sd = 1 Then s = -1.0

		; STAFFA di ferro scuro verticale (avvolge la tavola, sporge sopra e
		; sotto; nessun chiodo/ribattino, e' liscia)
		br = CreateCube(piv)
		ScaleEntity br, 0.075, 0.215, 0.014
		PositionEntity br, s*0.72, 0.55, 0.114
		EntityColor br, 104, 104, 112
		EntityShininess br, 0.3

		; CATENA: 6 maglie intrecciate, alternate frontale/di taglio; la prima
		; maglia AGGANCIA LA META' della staffa (appena davanti alla sua
		; faccia, cosi' si vede l'anello di attacco senza z-fight)
		For L = 0 To 5
			lk = BuildIronArc(piv, 0.055, 0.016, 0, 360, 8, 132, 132, 138, 0.3)
			PositionEntity lk, s*0.72, 0.50 - L*0.115, 0.132
			If (L And 1) Then RotateEntity lk, 0, 90, 0   ; maglia di taglio
		Next

		; CEPPO aperto: arco di ferro scuro arrugginito con l'apertura in
		; BASSO (manetta aperta); ben piu' SPESSO delle maglie della catena
		; (e' una fascia di ferro battuto, non un anello di catena)
		mn = BuildIronArc(piv, 0.16, 0.042, -35, 250, 7, 62, 50, 40, 0.15)
		PositionEntity mn, s*0.72, -0.285, 0.150
		RotateEntity mn, 8, s*20, s*8
		; punte a sferetta alle due estremita' aperte dell'arco
		For k = 0 To 1
			ae# = -35 : If k = 1 Then ae = 215
			tp2 = CreateSphere(8, mn)
			ScaleEntity tp2, 0.048, 0.048, 0.048
			PositionEntity tp2, 0.16*Cos(ae), 0.16*Sin(ae), 0
			EntityColor tp2, 62, 50, 40
			EntityShininess tp2, 0.15
		Next
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
; =========================================================================
; MURO CON NICCHIA SCAVATA (altare di Vi T=2, grande alcova T=59)
; Il vano e' DENTRO il muro: al posto del cubo pieno la cella-muro viene
; costruita con 5 blocchi (2 fianchi a tutta altezza, architrave sopra,
; parapetto sotto, tampone di fondo) che lasciano una cavita' rettangolare
; aperta verso la cella che la guarda. Le fasce esterne in MATTONI usano
; texture con UV riscalate alla faccia (stessa densita' dei cubi-muro,
; pattern jambtex); l'INTERNO del vano e' rivestito di pietra liscia, come
; nel 2D (la grafica g126 dentro mostra pietra puntinata, non mattoni).
; Pivot al CENTRO della cella-muro con +Z locale FUORI dall'apertura
; (stessa tabella yaw dei dispositivi: dir0=90 dir1=-90 dir2=0 dir3=180).
; Per l'altare della rinascita (T=2) il vano e' arredato: bordo-mensola,
; simbolo di Vi inciso sul fondo, due fiammelle votive verde/gialle.
; =========================================================================

; texture-muro con UV riscalate alla faccia visibile (hw,hh = mezze
; dimensioni): stessa densita' di mattoni dei cubi-muro pieni (cfr. jambtex)
Function WallTexScaled(hw#, hh#)
	t = LoadTexture("test_new.png")
	If t = 0 Then Return 0
	ScaleTexture t, CELL/(2.0*hw), CELL/(2.0*hh)
	Return t
End Function

Function BuildNicheWall(cx#, cz#, yaw#, dtype)
	; dimensioni del vano per tipo
	If dtype = 2 Then
		nhw# = 0.78 : ny0# = 0.70 : ny1# = 2.35 : nd# = 1.15   ; altare
	Else
		nhw# = 1.15 : ny0# = 0.50 : ny1# = 3.00 : nd# = 1.30   ; alcova grande
	EndIf
	piv = CreatePivot()
	PositionEntity piv, cx, 0, cz
	RotateEntity piv, 0, yaw, 0

	; ---- guscio esterno in mattoni (texture riscalata per ogni faccia) ----
	sw# = (CELL/2.0 - nhw)/2.0
	stx = WallTexScaled(sw, CELL/2.0)              ; fasce laterali strette
	For s = -1 To 1 Step 2
		bx = CreateCube(piv)
		ScaleEntity bx, sw, CELL/2.0, CELL/2.0
		PositionEntity bx, s*(nhw+sw), CELL/2.0, 0
		EntityColor bx, 255, 255, 255
		If stx <> 0 Then EntityTexture bx, stx, 0, 0
	Next
	atx = WallTexScaled(nhw, (CELL-ny1)/2.0)       ; architrave sopra il vano
	bx = CreateCube(piv)
	ScaleEntity bx, nhw, (CELL-ny1)/2.0, CELL/2.0
	PositionEntity bx, 0, (CELL+ny1)/2.0, 0
	EntityColor bx, 255, 255, 255
	If atx <> 0 Then EntityTexture bx, atx, 0, 0
	ptx = WallTexScaled(nhw, ny0/2.0)              ; parapetto sotto il vano
	bx = CreateCube(piv)
	ScaleEntity bx, nhw, ny0/2.0, CELL/2.0
	PositionEntity bx, 0, ny0/2.0, 0
	EntityColor bx, 255, 255, 255
	If ptx <> 0 Then EntityTexture bx, ptx, 0, 0
	; tampone dietro (la sua faccia visibile la copre il rivestimento)
	bd# = (CELL - nd)/2.0
	bx = CreateCube(piv)
	ScaleEntity bx, nhw, (ny1-ny0)/2.0, bd
	PositionEntity bx, 0, (ny0+ny1)/2.0, -nd/2.0
	EntityColor bx, 100, 100, 104

	; ---- rivestimento interno: pietra liscia (niente mattoni stirati) ----
	zmid# = CELL/2.0 - nd/2.0
	For s = -1 To 1 Step 2                         ; fianchi interni
		pnl = CreateCube(piv)
		ScaleEntity pnl, 0.015, (ny1-ny0)/2.0, nd/2.0
		PositionEntity pnl, s*(nhw-0.015), (ny0+ny1)/2.0, zmid
		EntityColor pnl, 105, 105, 109
	Next
	pnl = CreateCube(piv)                          ; cielo del vano
	ScaleEntity pnl, nhw, 0.015, nd/2.0
	PositionEntity pnl, 0, ny1-0.015, zmid
	EntityColor pnl, 82, 82, 86
	pnl = CreateCube(piv)                          ; piano del vano
	ScaleEntity pnl, nhw, 0.015, nd/2.0
	PositionEntity pnl, 0, ny0+0.015, zmid
	EntityColor pnl, 122, 122, 126
	zback# = CELL/2.0 - nd                         ; fondo del vano
	pnl = CreateCube(piv)
	ScaleEntity pnl, nhw, (ny1-ny0)/2.0, 0.015
	PositionEntity pnl, 0, (ny0+ny1)/2.0, zback+0.015
	EntityColor pnl, 100, 100, 104

	If dtype <> 2 Then Return

	; ------- arredo dell'ALTARE DELLA RINASCITA -------
	; bordo-mensola sul davanti del piano (dove si depongono le ossa)
	lp = CreateCube(piv)
	ScaleEntity lp, nhw, 0.05, 0.08
	PositionEntity lp, 0, ny0+0.05, CELL/2.0-0.08
	EntityColor lp, 175, 175, 179

	; simbolo di Vi inciso in alto sul fondo: targhetta scura + due onde
	tq = CreateCube(piv)
	ScaleEntity tq, 0.30, 0.16, 0.010
	PositionEntity tq, 0, ny1-0.42, zback+0.042
	EntityColor tq, 96, 96, 100
	For s = -1 To 1 Step 2
		onda = CreateCube(piv)
		ScaleEntity onda, 0.16, 0.022, 0.008
		PositionEntity onda, 0, ny1-0.42 + s*0.055, zback+0.055
		RotateEntity onda, 0, 0, s*12
		EntityColor onda, 225, 225, 228
	Next

	; fiammelle votive sui fianchi interni del vano (autoilluminate)
	For s = -1 To 1 Step 2
		fb = CreateSphere(8, piv)
		ScaleEntity fb, 0.06, 0.05, 0.05
		PositionEntity fb, s*(nhw-0.14), ny0+0.75, zback + nd*0.45
		EntityColor fb, 60, 200, 60
		EntityFX fb, 1
		fc = CreateCone(8, True, piv)
		ScaleEntity fc, 0.05, 0.10, 0.05
		PositionEntity fc, s*(nhw-0.24), ny0+0.79, zback + nd*0.45
		RotateEntity fc, 0, 0, s*65        ; punta inclinata verso il centro
		EntityColor fc, 250, 235, 60
		EntityFX fc, 1
		gt = CreateSphere(8, piv)
		ScaleEntity gt, 0.02, 0.026, 0.02
		PositionEntity gt, s*(nhw-0.17), ny0+0.66, zback + nd*0.45
		EntityColor gt, 200, 40, 30
		EntityFX gt, 1
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
	; l'eventuale oggetto-bersaglio del lancio sta per essere liberato col
	; resto: scollega il riferimento (verra' ri-agganciato per key piu' sotto
	; se l'oggetto e' ancora a terra; senno' il proiettile svanira' a vuoto)
	prjTgtEnt = 0
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
		ni = Int(brLine$(k+4))          ; k+3 = dbType (non serve)
		nm$ = brLine$(k+5)              ; nome motore ("APPLE", "BREAD", ...)
		k = k + 6
		ok = (floN < FLOBJ_MAX And fx >= 0 And fx < w And fy >= 0 And fy < h And ni >= 0 And ni <= 255)
		; le celle-MURO contengono oggetti "invisibili" (es. l'equipaggiamento
		; dei campioni vive dentro la cella dello specchio): saltate, come nel 2D
		If ok Then
			If Mid$(grid$(fy), fx+1, 1) = "0" Then ok = 0
		EndIf
		If ok Then
			; mesh vera se disponibile (floorobj/ni<NI>.b3d o procedurale
			; mela/pane); 0 = si ricade sul billboard con l'icona 2D
			proto = FloorObjProto(ni, nm$)
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
			If proto <> 0 Then
				; istanza della mesh: origine alla BASE, appoggiata alla stessa
				; quota dei billboard (0.18: sopra erba e pedane). Yaw FISSO
				; pseudo-casuale da cella+quadrante (varieta' tra le istanze;
				; il main loop NON la ruota verso la camera: floIsMesh=1)
				e = CopyEntity(proto)
				ShowEntity e
				RotateEntity e, 0, (fx*73 + fy*131 + fp*47) Mod 360, 0
				PositionEntity e, (w-1-fx)*CELL + dxo + lift*0.02, 0.18 + lift*0.06, fy*CELL + dzo + lift*0.02
				floIsMesh(floN) = 1
				; raccolta col click: pickabile, nome = cella+quadrante
				EntityPickMode e, 3
				NameEntity e, "flobj:" + fx + ":" + fy + ":" + fp
			Else
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
				e = CreateCube()
				; lastra VERTICALE stile sprite DM (in piedi, non stesa): il main
				; loop la tiene ruotata verso la camera (billboard ad asse Y).
				; Gli impilati salgono e slittano un filo in diagonale (niente
				; piani complanari in z-fight).
				; meta' del decal steso di prima (richiesta utente: -50%)
				fhw# = FLOBJ_HW * 0.5
				ScaleEntity e, fhw, fhw, 0.01
				; base a ~0.18: sopra l'erba (top 0.15) e sopra la lastra delle
				; pedane (top ~0.179), cosi' un oggetto posato su una pedana si vede
				PositionEntity e, (w-1-fx)*CELL + dxo + lift*0.02, 0.18 + fhw + lift*0.06, fy*CELL + dzo + lift*0.02
				EntityColor e, 255, 255, 255
				If floTex(ni) <> 0 Then EntityTexture e, floTex(ni)
				floIsMesh(floN) = 0
				; raccolta col click: pickabile, nome = cella+quadrante
				EntityPickMode e, 3
				NameEntity e, "flobj:" + fx + ":" + fy + ":" + fp
			EndIf
			; proiettile in volo: se questo e' l'oggetto ATTERRATO del lancio
			; (stesso NI, avanti lungo la traiettoria, corsia giusta), resta
			; nascosto finche' il proiettile non arriva li' (ThrowProjEnd lo
			; mostra). Il rebuild rilega per key: le entity cambiano a ogni
			; refresh ma la cella+quadrante d'atterraggio no.
			If prjEnt <> 0 And ni = prjNI Then
				exw# = EntityX(e) : ezw# = EntityZ(e)
				fwd# = (exw - prjSX)*prjDX + (ezw - prjSZ)*prjDZ
				lat# = Abs((exw - prjSX)*prjDZ - (ezw - prjSZ)*prjDX)
				If fwd > CELL*0.4 And lat < CELL*0.8 Then
					If prjTgt = 0 Or key = prjTgtKey Then
						prjTgt = 1
						prjTgtKey = key
						prjTgtEnt = e
						prjTX = exw : prjTY = EntityY(e) : prjTZ = ezw
						HideEntity e
					EndIf
				EndIf
			EndIf
			floKey(floN) = key
			floEnt(floN) = e
			floN = floN + 1
		EndIf
	Next
End Function

; ------------------------------------------------- lancio: proiettile 3D
; texture dell'oggetto a terra per objNI, caricata pigramente (stessa logica
; e stessa cache floTex() di BuildFloorObjs; _custom.png vince sempre).
Function FloorObjTex(ni)
	If ni < 0 Or ni > 255 Then Return 0
	If floTex(ni) = 0 Then
		cust$ = "floorobj/ni" + ni + "_custom.png"
		If FileType(cust$) = 1 Then
			floTex(ni) = LoadTexture(cust$, 2)
		Else
			floTex(ni) = LoadTexture("floorobj/ni" + ni + ".png", 2)
		EndIf
		If floTex(ni) <> 0 Then ScaleTexture floTex(ni), 1, -1
	EndIf
	Return floTex(ni)
End Function

; chiamata al CLICK di lancio: memorizza NI, punto di partenza (davanti al
; party, spostato sulla corsia sx/dx come nel 2D) e direzione mondo. Il
; proiettile parte solo quando lo STATE conferma la mano svuotata (il motore
; puo' rifiutare il lancio: porta, peso, HandChar).
; Versori (verificati col pomello): N: avanti -Z, destra -X; E: avanti -X,
; destra +Z; S: avanti +Z, destra +X; W: avanti +X, destra -Z.
Function ThrowPrep(side, ppx, ppy, ppf, w)
	fx# = 0 : fz# = 0 : rxw# = 0 : rzw# = 0
	If ppf = 0 Then fz = -1 : rxw = -1
	If ppf = 1 Then fx = -1 : rzw = 1
	If ppf = 2 Then fz = 1 : rxw = 1
	If ppf = 3 Then fx = 1 : rzw = -1
	s# = -CELL*0.22 : If side = 1 Then s = CELL*0.22
	prjPendNI = hcHandNI
	prjPendMs = MilliSecs()
	prjPendX = (w-1-ppx)*CELL + fx*CELL*0.3 + rxw*s
	prjPendZ = ppy*CELL + fz*CELL*0.3 + rzw*s
	prjPendDX = fx : prjPendDZ = fz
End Function

; spawn del proiettile (mano appena svuotata): mesh vera se disponibile,
; altrimenti billboard con l'icona, come gli oggetti a terra.
Function SpawnThrowProj()
	If prjEnt <> 0 Then ThrowProjEnd()       ; un proiettile alla volta
	ni = prjPendNI
	nm$ = ""
	If ni >= 0 And ni <= 255 Then nm$ = objName$(ni)
	proto = FloorObjProto(ni, nm$)
	If proto <> 0 Then
		prjEnt = CopyEntity(proto)
		ShowEntity prjEnt
		prjIsMesh = 1
	Else
		prjEnt = CreateCube()
		fhw# = FLOBJ_HW * 0.5
		ScaleEntity prjEnt, fhw, fhw, 0.01
		EntityColor prjEnt, 255, 255, 255
		t = FloorObjTex(ni)
		If t <> 0 Then EntityTexture prjEnt, t
		prjIsMesh = 0
	EndIf
	prjNI = ni
	prjX = prjPendX : prjY = 1.5 : prjZ = prjPendZ
	prjSX = prjPendX : prjSZ = prjPendZ
	prjDX = prjPendDX : prjDZ = prjPendDZ
	prjMs = MilliSecs()
	prjTgt = 0 : prjTgtKey = -1 : prjTgtEnt = 0
	PositionEntity prjEnt, prjX, prjY, prjZ
End Function

; fine volo: mostra l'oggetto vero (tenuto nascosto da BuildFloorObjs) e
; libera il proiettile. Usata anche per i timeout (atterraggio mai visto).
Function ThrowProjEnd()
	If prjTgtEnt <> 0 Then ShowEntity prjTgtEnt
	If prjEnt <> 0 Then FreeEntity prjEnt
	prjEnt = 0 : prjNI = -1
	prjTgt = 0 : prjTgtKey = -1 : prjTgtEnt = 0
End Function

; ------------------------------------------------- raccolta: quadrante->regione
; Converte l'oggetto cliccato (cella fx,fy + quadrante assoluto fp: 0=NW 1=NE
; 2=SE 3=SW; griglia x=est y=sud) nella REGIONE del viewport 2D che TAG01a148
; usa per raccogliere: 0/1 = cella del party sx/dx, 2/3 = cella davanti sx/dx.
; -1 = fuori portata (regola DM: solo propria cella e cella davanti).
; "Destra a schermo" = facing ruotato N->E, E->S, S->W, W->N: verificato
; empiricamente col pomello porta (mondo specchiato su X + left-handed di
; Blitz3D si annullano: vale l'intuizione da mappa).
; ppx/ppy/ppf = posizione e facing del party PASSATI Dal chiamante: px/py/pf
; del main NON sono Global (le funzioni li ricevono sempre come parametro,
; vedi DrawHUD(pf)) e dentro una Function varrebbero 0.
Function FloorPickRegion(fx, fy, fp, ppx, ppy, ppf)
	dx = 0 : dy = 0
	If ppf = 0 Then dy = -1
	If ppf = 1 Then dx = 1
	If ppf = 2 Then dy = 1
	If ppf = 3 Then dx = -1
	base = -1
	If fx = ppx And fy = ppy Then base = 0
	If fx = ppx + dx And fy = ppy + dy Then base = 2
	If base < 0 Then Return -1
	; offset del quadrante dal centro cella (griglia)
	ox = -1 : If fp = 1 Or fp = 2 Then ox = 1
	oy = -1 : If fp = 2 Or fp = 3 Then oy = 1
	; versore "destra" nel piano griglia
	rx = 0 : ry = 0
	If ppf = 0 Then rx = 1
	If ppf = 1 Then ry = 1
	If ppf = 2 Then rx = -1
	If ppf = 3 Then ry = -1
	If ox*rx + oy*ry > 0 Then Return base + 1
	Return base
End Function

; =========================================================================
; OGGETTI A TERRA IN VERA GEOMETRIA 3D (mela, pane, ...)
; Prototipo NASCOSTO per objNI, istanziato con CopyEntity a ogni refresh
; (BuildFloorObjs ricostruisce tutto da zero: i prototipi sopravvivono).
; Precedenza: floorobj/ni<NI>.b3d (modellato a mano, asset EDITABILE) ->
; procedurale per nome motore -> 0 = billboard classico con l'icona 2D.
; CONVENZIONE: origine del prototipo alla BASE (appoggia sulla y passata a
; PositionEntity); CopyEntity copia l'intera gerarchia (picciolo incluso).
; Anche i tentativi falliti restano in cache (floMeshTry): niente FileType
; ripetuti a ogni refresh del pavimento.
; =========================================================================
Function FloorObjProto(ni, nome$)
	If ni < 0 Or ni > 255 Then Return 0
	If floMeshTry(ni) Then Return floMeshP(ni)
	m = 0
	f$ = "floorobj/ni" + ni + ".b3d"
	If FileType(f$) = 1 Then m = LoadMesh(f$)
	If m = 0 Then
		n$ = Upper$(Trim$(nome$))
		; nome ignoto (chiamante senza payload): NON cacheare il "no",
		; ritenta quando il nome arrivera' (altrimenti un solo tentativo
		; a vuoto condannerebbe quell'NI al billboard per sempre)
		If n$ = "" Then Return 0
		If n$ = "APPLE" Then m = BuildAppleMesh()
		If n$ = "BREAD" Then m = BuildBreadMesh()
	EndIf
	floMeshTry(ni) = 1
	If m <> 0 Then HideEntity m
	floMeshP(ni) = m
	Return m
End Function

; mela stilizzata: sfera deformata a profilo-mela (corpo schiacciato, fossette
; ai poli) + picciolo cilindrico inclinato. ~130 triangoli, ingombro ~0.48
; unita' (CELL=4 -> ~12% del tile), come l'icona billboard che sostituisce.
Function BuildAppleMesh()
	m = CreateSphere(8)
	s = GetSurface(m, 1)
	For v = 0 To CountVertices(s)-1
		vx# = VertexX(s, v) : vy# = VertexY(s, v) : vz# = VertexZ(s, v)
		rr# = Sqr(vx*vx + vz*vz)             ; 0 ai poli, 1 all'equatore
		; la quota pesata dal raggio orizzontale scava i poli (fossette)
		VertexCoords s, v, vx, vy*(0.62 + 0.33*rr), vz
	Next
	ScaleMesh m, 0.24, 0.24, 0.24
	UpdateNormals m
	; origine alla base + quota della cima (per piazzare il picciolo)
	miny# = 0 : topy# = 0
	For v = 0 To CountVertices(s)-1
		If VertexY(s, v) < miny Then miny = VertexY(s, v)
		If VertexY(s, v) > topy Then topy = VertexY(s, v)
	Next
	PositionMesh m, 0, -miny, 0
	topy = topy - miny
	EntityColor m, 192, 32, 26               ; rosso mela
	st = CreateCylinder(6, 1, m)             ; picciolo bruno nella fossetta
	ScaleEntity st, 0.022, 0.075, 0.022
	PositionEntity st, 0.025, topy + 0.03, 0
	RotateEntity st, 0, 0, 14
	EntityColor st, 92, 62, 34
	Return m
End Function

; pane "pan carre'" come lo sprite 2D di DM: mattoncino dai bordi arrotondati
; (superellissoide: sfera coi componenti elevati a potenza <1 spinge i punti
; verso le facce di un cubo morbido), fondo tagliato piatto, crosta bruna.
Function BuildBreadMesh()
	m = CreateSphere(8)
	s = GetSurface(m, 1)
	For v = 0 To CountVertices(s)-1
		vx# = VertexX(s, v) : vy# = VertexY(s, v) : vz# = VertexZ(s, v)
		nx# = Sgn(vx) * Abs(vx)^0.45
		ny# = Sgn(vy) * Abs(vy)^0.45
		nz# = Sgn(vz) * Abs(vz)^0.45
		If ny < -0.82 Then ny = -0.82        ; fondo piatto: appoggia bene
		VertexCoords s, v, nx, ny, nz
	Next
	ScaleMesh m, 0.26, 0.105, 0.145          ; filoncino: lungo (X), basso (Y)
	UpdateNormals m
	miny# = 0
	For v = 0 To CountVertices(s)-1
		If VertexY(s, v) < miny Then miny = VertexY(s, v)
	Next
	PositionMesh m, 0, -miny, 0              ; origine alla base
	EntityColor m, 105, 54, 24               ; crosta bruna scura (la torcia scalda)
	; semini chiari sul dorso: mini-sfere FIGLIE sparse in modo deterministico
	; (seni come hash). Figli = colore proprio ma stessa luce; CopyEntity in
	; BuildFloorObjs copia l'intera gerarchia, semini compresi.
	For i = 1 To 16
		sx# = Sin(i*57.3) * 0.75
		sz# = Sin(i*93.7 + 40) * 0.72
		; quota ESATTA del dorso: inverto la mappa superellissoide (potenza
		; 1/0.45) per tornare alla sfera, poi rimappo la y con la potenza 0.45
		px# = Abs(sx) ^ (1.0/0.45)
		pz# = Abs(sz) ^ (1.0/0.45)
		pq# = 1.0 - px*px - pz*pz
		If pq < 0 Then pq = 0
		yu# = Sqr(pq) ^ 0.45
		sd = CreateSphere(4, m)
		ScaleEntity sd, 0.013, 0.007, 0.009
		PositionEntity sd, sx*0.26, yu*0.105 + (-miny) - 0.003, sz*0.145
		RotateEntity sd, 0, i*39, 0
		EntityColor sd, 238, 222, 190
	Next
	Return m
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
