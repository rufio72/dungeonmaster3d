; ==========================================================================
; dm3d_ui.bb - HUD, BARRA MAGIE, RITRATTI MURALI
; Incluso da dm3d.bb (inclusione TESTUALE Blitz3D: stesso scope globale).
; Contiene SOLO funzioni; globals/const/dim stanno nel main. Non modificare
; l'ordine di Include senza motivo.
; ==========================================================================

; ritratto murale acceso/spento: lo specchio usato nel 2D resta sbiadito
; (PORTRAIT_InActive). Specchio usato -> il volto svanisce quasi del tutto
; via alpha (PORT_USED_ALPHA), lasciando in vista la cornice scura dietro:
; effetto "cornice quasi vuota". Con alpha 0 la cornice resta completamente vuota.
Function SetPortraitActive(ord, act)
	If ord < 0 Or ord >= NPORT Then Return
	If portEnt(ord) = 0 Then Return
	If act = 0 Then
		EntityColor portEnt(ord), 255, 255, 255
		EntityAlpha portEnt(ord), PORT_USED_ALPHA
	Else
		EntityColor portEnt(ord), 255, 255, 255
		EntityAlpha portEnt(ord), 1.0
	EndIf
End Function

; risposta "PORTS <n>" gia' in brLine$: count + blocchi di 5 righe
; (fx, fy, dir, ord, act) — qui interessa solo ord+act per lo sbiadimento.
; Il reclutamento non arriva come act=0: il bridge marca il ritratto
; PORTRAIT_HidePortrait e lo TOGLIE dalla lista (specchio vuoto come nel 2D).
; Perche' il quadro 3D sbiadisca lo trattiamo come "usato": ogni portEnt gia'
; piazzato che NON compare piu' in questa risposta viene sbiadito (act=0).
Function ParsePorts()
	pcount = Int(brLine$(0))
	For i = 0 To NPORT-1
		portSeen(i) = 0
	Next
	pk = 1
	For i = 1 To pcount
		ord = Int(brLine$(pk+3))
		If ord >= 0 And ord < NPORT Then portSeen(ord) = 1
		SetPortraitActive(ord, Int(brLine$(pk+4)))
		pk = pk + 5
	Next
	For i = 0 To NPORT-1
		If portEnt(i) <> 0 And portSeen(i) = 0 Then SetPortraitActive(i, 0)
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
