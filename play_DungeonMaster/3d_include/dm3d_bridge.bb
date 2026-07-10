; ==========================================================================
; dm3d_bridge.bb - CLIENT TCP DEL PONTE + INPUT MOVIMENTO
; Incluso da dm3d.bb (inclusione TESTUALE Blitz3D: stesso scope globale).
; Contiene SOLO funzioni; globals/const/dim stanno nel main. Non modificare
; l'ordine di Include senza motivo.
; ==========================================================================

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
	If Left$(hdr$, 2) <> "OK" And Left$(hdr$, 4) <> "PUSH" And Left$(hdr$, 5) <> "SHEET" And Left$(hdr$, 5) <> "PORTS" And Left$(hdr$, 5) <> "FLOOR" Then Return -1
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
