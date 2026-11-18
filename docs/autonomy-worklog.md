# AMC Autonomie-Worklog

Protokoll der autonomen Arbeitssession vom 18.11.2026. Jeder Eintrag sagt: wann, was, warum, Testergebnis, offene Punkte.
Zeiten in UTC. Arbeitsbranch: `claude/new-session-z2yz2c`.

Reihenfolge laut Auftrag: Teil D (Fehlerprüfung) → Teil A + B (Unattended-Modus, Protokoll) → Teil C (lange Läufe) → Teil E (Funktionsvergleich) → Abschluss.

---

## 2026-11-18 22:05 – Bestandsaufnahme und Werkzeuge

**Was:** Repository gelesen (Engine, Sicherheitsfilter, Phone-Policy, Tools, Prompts, Worker, Room-Schicht, LlmClient, Bridge-Client, Python-Daemon, Telegram-Gateway, Teile der UI). Android-SDK (Plattform 35, Build-Tools 35) in der Session installiert, damit Gradle-Build und Unit-Tests hier wirklich laufen. Python-Abhängigkeit `websockets` installiert.

**Warum:** Ohne SDK hätte ich keinen Kotlin-Test ausführen können; der Auftrag verlangt, nichts als getestet zu melden, was nicht lief.

**Ergebnis Ausgangszustand:**
- Python-Bridge-Tests: 51 Tests, alle grün (ohne `websockets` scheitert `test_bridge_daemon` am Import – das ist eine fehlende Abhängigkeit der Umgebung, kein Code-Fehler).
- Shell-Syntax (`bash -n` für setup.sh, amc, local_model_manager.sh): ok.
- E2E-Suite `tests/e2e/runner.py --all`: 236 Tests, alle grün.
- Gradle `testDebugUnitTest assembleDebug`: läuft (erster Lauf lädt Abhängigkeiten).

**Offen:** Ergebnis des ersten Gradle-Laufs (folgt im nächsten Eintrag).

## 2026-11-18 22:30 – Ausgangszustand Gradle, Teil D: Befunde erfasst

**Was:** Erster vollständiger Gradle-Lauf (`testDebugUnitTest assembleDebug`) – 361 Unit-Tests grün, Debug-APK gebaut (6½ min inkl. Download). Danach die ganze App auf echte Fehler geprüft und elf Befunde (#29–#39) im Review-Format in `docs/AMC-Autonomie-Review.md` eingetragen.

**Wichtigste Funde in Alltagssprache:**
- #29: Bei Mehrfach-Bildschirmaktionen („screen_actions") wurde die vom Nutzer gepflegte Sperrliste ignoriert – freigegebene Apps waren mitten im Batch gesperrt.
- #30: Das geheime Bridge-Token konnte per Shell-Befehl gelesen oder überschrieben werden, ohne dass jemand gefragt wurde.
- #31/#32: Gemini meldete abgerissene Antworten als fertig, und der API-Schlüssel stand zusätzlich in der Adresszeile.
- #33: „Stopp" im Chat hat auch den Befehl im Terminal-Tab abgebrochen.
- #34–#39: kleinere Logik-, Bedien- und Barrierefreiheitsfehler (Regeln entfernen wirkt nicht, Tab springt beim Drehen zurück, zu kleine Tippflächen, http-Links scheitern, Audit-Tabelle wächst unbegrenzt, Telegram-Entkoppeln stoppt laufende Aufgaben nicht).

**Tests:** nur Doku geändert, kein Build nötig.

**Offen:** Behebung in Reihenfolge der Schwere (nächste Einträge).

## 2026-11-18 22:45 – Teil D: Fehler #29–#37 und #39 behoben

**Was:** Zehn Befunde mit je einem Regressionstest behoben, jeweils ein eigener Commit:
- #29 Batch-Aktionen nutzen jetzt die Sperrliste aus den Einstellungen (ca1b6f5).
- #30 Shell-Befehle, die das Bridge-Token lesen/kopieren/ändern oder `amc token` aufrufen, sind in jedem Modus gesperrt – auch in Skripten (623b2f9). Das ist bereits Teil der „Untergrenze" aus Teil A.
- #34 Entfernte eigene Regeln wirken sofort nicht mehr (623b2f9).
- #31/#32 Gemini: abgerissene Antworten werden wiederholt statt als fertig gewertet, Überlastung wird wiederholt, API-Schlüssel nur noch im Header (d088cab).
- #33 Stopp beendet nur noch den eigenen Befehl des Agenten, nicht mehr den im Terminal-Tab (80de080). Dafür gibt es jetzt einen Fake-Bridge-Daemon für Engine-Tests.
- #37 `web_fetch http://…` wird für fremde Server automatisch über https geholt (bea891f).
- #35/#36 Tab bleibt beim Drehen erhalten; Regel-Chips sind mit 48-dp-Fläche und TalkBack-Text entfernbar (ba575f1). Dafür Compose-UI-Tests unter Robolectric eingerichtet.
- #39 Entkoppeln eines Telegram-Kontos in der App stoppt dessen laufende Aufgabe (a658e72).

**Warum so:** Jeweils der kleinste Eingriff, der den Auslöser beseitigt (z. B. Sperrliste durchreichen statt Executor umbauen).

**Tests:** Gradle 372 Unit-Tests grün (vorher 361, +11 neue), Debug-APK gebaut; Python 51 grün; `bash -n` ok; E2E 236 grün.

**Offen:** #38 (Audit-Tabelle unbegrenzt) wird mit dem neuen Audit in Teil B behoben.
