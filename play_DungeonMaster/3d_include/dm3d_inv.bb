; ==========================================================================
; dm3d_inv.bb - INVENTARIO / SCHEDA CAMPIONE (pannello, drag-and-drop, occhio, food/water)
; Incluso da dm3d.bb (inclusione TESTUALE Blitz3D: stesso scope globale).
; Contiene SOLO funzioni; globals/const/dim stanno nel main. Non modificare
; l'ordine di Include senza motivo.
; ==========================================================================

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
	; colonna destra a scala 2 (pannello attacco, HUD-3): atlanti SAGOMA
	; g<N>_2s (celle 32x32 con alpha, l'arma va disegnata come sagoma NERA
	; sul riquadro ciano — come ShrinkBLT+Byte20194 nel 2D), pannello del
	; menu azioni g10_2f e retino d'ombra (pannelli in cooldown)
	For a = 0 To 6
		invAtlS(a) = LoadAnimImage("invgfx/g" + (42+a) + "_2s.png", 32, 32, 0, 32)
	Next
	invImg10 = LoadImage("invgfx/g10_2f.png")
	shadeImg = LoadImage("shade.png")

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
			Else If Left$(ln$, 7) = "abutton" And n >= 5 And atkN < 5 Then
				; pannelli arma (Buttons17952: 116-119, spazio mouse 2D)
				atkCode(atkN) = pliV(0)
				atkBX1(atkN) = pliV(1) : atkBX2(atkN) = pliV(2)
				atkBY1(atkN) = pliV(3) : atkBY2(atkN) = pliV(4)
				atkN = atkN + 1
			Else If Left$(ln$, 7) = "obutton" And n >= 5 And obtN < 5 Then
				; menu azioni aperto (Buttons17892: 112 pass, 113-115 opzioni)
				obtCode(obtN) = pliV(0)
				obtBX1(obtN) = pliV(1) : obtBX2(obtN) = pliV(2)
				obtBY1(obtN) = pliV(3) : obtBY2(obtN) = pliV(4)
				obtN = obtN + 1
			Else If Left$(ln$, 5) = "amenu" And n >= 4 Then
				; rect del pannello g10 per k opzioni (amenu<k> x1 x2 y1 y2)
				k = Int(Mid$(ln$, 6, 1))
				If k >= 1 And k <= 3 Then
					amenuX1(k) = pliV(0) : amenuX2(k) = pliV(1)
					amenuY1(k) = pliV(2) : amenuY2(k) = pliV(3)
				EndIf
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
