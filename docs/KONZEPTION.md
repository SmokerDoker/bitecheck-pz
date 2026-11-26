# PZ Java-Mod-Register – Spezifikation

1. November 2026 · Noah Lober

> Ursprüngliche Konzeption des Projekts (bisher als Dokument in claude.ai abgelegt). Was davon
> umgesetzt ist und wo BiteCheck abweicht, steht im letzten Abschnitt
> [Umsetzungsstand und Abweichungen](#umsetzungsstand-und-abweichungen).

## Ziel und Grundprinzipien

Das Register macht Java-Mods für Project Zomboid nachprüfbar: Ohne Rückfrage lädt der Launcher nur eine `.jar`, deren Hash im Register steht, die nachweislich aus öffentlichem Quellcode gebaut wurde und die von Menschen geprüft ist.

Die JVM kann fremden Code seit dem Wegfall des SecurityManagers nicht mehr einsperren. Sicherheit entsteht deshalb nicht im Launcher, sondern durch Transparenz, Nachvollziehbarkeit und Prüfung.

### Grundprinzipien

- **Nur öffentlicher Quellcode:** Ins kuratierte Register kommen ausschließlich Mods, deren vollständiger Quellcode öffentlich und unter einer Lizenz verfügbar ist, die Prüfung und Neubau erlaubt.
- **Freigabe pro Version:** Jede Freigabe gilt für genau eine Version, identifiziert über den SHA-256-Hash der `.jar`. Ein Update ist ungeprüft, bis es selbst freigegeben wurde.
- **Gebaut, nicht hochgeladen:** Die ausgelieferte `.jar` stammt aus dem CI-Build des Registers, nicht vom Autor. Der Autor liefert Quellcode, das Register liefert das Binary.
- **Alles öffentlich nachvollziehbar:** Einreichungen, Reviews, Flags und Entscheidungen liegen in einem öffentlichen Git-Repository. Jeder kann gegenprüfen.
- **Verteiltes Vertrauen:** Keine einzelne Person kann eine Mod allein freigeben.

### Ungeprüfte Mods und Lua-Mods

Der Launcher lädt wie ZombieBuddy auch Java-Mods, die nicht freigegeben sind. Für jede ungeprüfte Mod zeigt er vor dem Start eine Warnung und lädt sie nur nach Bestätigung durch den Nutzer. Die Zustimmung gilt für genau diesen Hash; ändert sich die Datei, fragt er erneut.

Geschlossene Mods kommen nicht ins Register. Ein Reviewer kann sie aber dekompilieren und eine Prüfnotiz anlegen (Hash, Befunde, Reviewer, ohne Datei und Quellcode). Die Notiz erscheint dann in der Warnung, damit der Nutzer informiert entscheiden kann.

Lua-Mods lädt das Spiel selbst über seinen Mod-Manager und den Workshop. Der Launcher greift dort nicht ein: Lua-Mods funktionieren unverändert und brauchen keine Registrierung.

### Für Modder: in drei Schritten ins Register

Einreichen darf nicht mehr Aufwand sein als eine Workshop-Veröffentlichung. Alles Technische (reproduzierbarer Build, Hashes, Signaturen, Scanner) erledigen Vorlage, Tool und CI.

1. **Vorlage nutzen:** Neues Projekt aus dem Template-Repo erstellen oder das Gradle-Plugin in ein bestehendes Projekt eintragen. Es setzt alle Build-Einstellungen automatisch.
2. **Einmal einrichten:** `pzreg login` verknüpft den GitHub-Account und erzeugt den Signaturschlüssel. Kein manuelles Schlüsselmanagement.
3. **Einreichen:** Git-Tag setzen und `pzreg submit` ausführen. Alternativ erledigt die mitgelieferte GitHub Action das bei jedem Release-Tag automatisch. Das Tool öffnet den Pull Request im Register selbst.

Danach sieht der Modder Build-Status, Scanner-Ergebnis und Reviews auf einer Statusseite und wird bei Rückfragen benachrichtigt. Ziel: Build-Ergebnis unter 15 Minuten nach dem Tag, Freigabe innerhalb weniger Tage.

Bis zur Freigabe ist die Mod nicht blockiert: Spieler können sie wie jede ungeprüfte Mod mit Warnung laden.

## Architektur-Überblick

Das System besteht aus fünf Bausteinen: Register-Repo, CI-Pipeline mit Scanner, Download-Spiegel, CLI-Tool für Autoren und Reviewer sowie eine Prüfschicht im Launcher vor ZombieBuddy.

Der Autor liefert nur Quellcode; die `.jar`, die beim Spieler ankommt, stammt immer aus der CI und wird vom Launcher vor dem Laden gegen das Register geprüft.

## Datenmodell des Registers

Das Register ist ein Git-Repository mit einer Datei pro Objekt (TOML oder JSON). Jede Änderung ist ein Pull Request und damit versioniert, signiert und diskutierbar.

```text
registry/
  mods/<mod-id>/mod.toml
  mods/<mod-id>/releases/<version>.toml
  mods/<mod-id>/reviews/<version>/<reviewer>.toml
  mods/<mod-id>/flags/<flag-id>.toml
  keys/authors/<name>.pub
  keys/reviewers/<name>.pub
  keys/registry.pub
  notes/<sha256>.toml        # Prüfnotizen für geschlossene Mods
```

| Objekt | Wichtigste Felder | Wer schreibt |
|---|---|---|
| Mod | ID, Name, Autor, Quellrepo-URL, Lizenz, Workshop-ID (optional), Status (aktiv, gesperrt, zurückgezogen) | Autor per Einreichung |
| Release | Version, Git-Commit-Hash, Build-Rezept (JDK, Gradle-Version, Befehle), erwarteter SHA-256 der `.jar`, unterstützte PZ- und ZombieBuddy-Versionen, Autor-Signatur, Register-Signatur | Autor, CI ergänzt Hash und Register-Signatur |
| Review | Release-Hash, Reviewer, Ergebnis (freigegeben, abgelehnt, Rückfragen), geprüfter Umfang, Befunde, Scanner-Report-Hash, Signatur | Reviewer |
| Flag | Release-Hash, Melder, Schwere (Info, Verdacht, kritisch), Beschreibung, Status, Entscheidung | jeder Nutzer mit Konto |
| Schlüssel | öffentlicher Schlüssel, Inhaber, Rolle, gültig ab/bis, Widerruf | Maintainer |
| Prüfnotiz | SHA-256, Herkunft, Befunde, Reviewer, Signatur, ausdrücklich ohne Datei und Quellcode | Reviewer |

Der Status eines Release ergibt sich aus den Objekten, nicht aus einem Feld, das jemand setzen kann: **freigegeben** = reproduzierbarer Build erfolgreich, Scanner ohne harten Treffer, mindestens zwei gültige Freigaben, kein offenes kritisches Flag.

## Reproduzierbare Builds per CI

Eine Mod gilt erst als gebaut, wenn zwei unabhängige Builds aus demselben Commit bitgenau dieselbe `.jar` ergeben. Nur dieser Hash darf ins Release.

### Ablauf pro Release

1. Autor reicht Commit-Hash und Build-Rezept ein, nicht die `.jar`.
2. CI klont das Quellrepo genau auf diesem Commit und spiegelt es ins Register (der Quellcode bleibt erhalten, auch wenn der Autor sein Repo löscht).
3. Build A läuft in einem festen Container-Image (gepinnt per Digest, nicht per Tag) ohne Netzwerkzugriff; Abhängigkeiten kommen aus einem vorab geprüften, per Hash gepinnten Cache.
4. Build B läuft auf einem zweiten, unabhängigen Runner (anderer Anbieter oder ein freiwilliger Rebuilder).
5. Stimmen beide SHA-256-Werte überein, signiert die CI das Release mit dem Register-Schlüssel. Weichen sie ab, wird das Release abgelehnt und der Diff der beiden `.jar` im PR angehängt.

### Anforderungen an den Build (die Vorlage setzt sie automatisch)

- Gradle-Build mit `preserveFileTimestamps = false` und `reproducibleFileOrder = true` für alle Archive.
- Fester JDK-Hersteller und feste Version (passend zur Java-Version des Spiels).
- Alle Abhängigkeiten mit Version und Prüfsumme gepinnt (Gradle Dependency Verification, `verification-metadata.xml`).
- Keine Build-Schritte, die zur Build-Zeit etwas aus dem Netz laden oder Code generieren, der vom Zeitpunkt abhängt.
- Keine eingebetteten Fremd-`.jar` oder nativen Bibliotheken, deren Quellcode nicht ebenfalls im Repo liegt.
- Die PZ-Spielklassen werden nur zum Kompilieren referenziert (`compileOnly`), nie in die `.jar` gepackt.

## Signaturen von Autor und Register

Jede Signatur beantwortet genau eine Frage. Der Launcher verlangt alle drei Arten, bevor er eine Mod lädt.

| Signatur | Signiert | Beweist | Beweist nicht |
|---|---|---|---|
| Autor | Release-Datei (Commit-Hash, Version, Build-Rezept) | Dieser Commit stammt vom eingetragenen Autor | Dass der Code harmlos ist |
| Reviewer | Review-Datei mit Release-Hash und Ergebnis | Diese Person hat genau diese Version geprüft und freigegeben | Dass die Prüfung vollständig war |
| Register | Release-Datei samt `.jar`-Hash, nach erfolgreichem Doppel-Build | Die `.jar` entspricht bitgenau dem Quellcode dieses Commits | Dass Autor oder Reviewer vertrauenswürdig sind |

Technik: Signaturen mit Ed25519 (z. B. per minisign oder SSH-Schlüssel mit `ssh-keygen -Y sign`). Optional zusätzlich Sigstore, um Signaturen in einem öffentlichen Transparenzlog zu verankern.

### Schlüsselverwaltung

- Autoren registrieren ihren Schlüssel mit der ersten Einreichung. Der PR muss aus dem Konto kommen, dem das Quellrepo gehört.
- Der Register-Schlüssel liegt nur in der CI (in einem Secret Store oder Hardware-Schlüssel), nie bei einer Person.
- Ein kompromittierter Schlüssel wird widerrufen. Alle Releases und Reviews mit diesem Schlüssel nach dem angegebenen Zeitpunkt werden automatisch auf „ungeprüft“ zurückgesetzt.
- Schlüsselwechsel des Autors: Der neue Schlüssel muss vom alten signiert sein, sonst durchläuft er die Erstprüfung erneut.

## Automatische Prüfung und menschliches Review

Der Scanner entscheidet nicht über „sicher“, er sortiert vor: Harte Treffer blockieren sofort, weiche Treffer müssen Reviewer ausdrücklich begründen. Er prüft den Bytecode der fertigen `.jar` (nicht nur den Quellcode), weil nur der tatsächlich geladen wird.

Werkzeuge: Bytecode-Analyse mit ASM oder SootUp für Aufrufe und Konstanten, Abhängigkeitsprüfung gegen OSV/CVE-Datenbanken, Secret-Scan auf eingebettete Tokens und URLs.

| Regel | Was gesucht wird | Stufe |
|---|---|---|
| Prozessstart | `Runtime.exec`, `ProcessBuilder`, `Desktop.open` | hart |
| Code nachladen | eigene ClassLoader, `defineClass`, `MethodHandles.Lookup.defineClass`, `URLClassLoader` | hart |
| Native Code | `System.load`, `System.loadLibrary`, JNA, `.dll`/`.so`/`.dylib` in der `.jar` | hart |
| Unsichere Low-Level-APIs | `sun.misc.Unsafe`, `java.lang.instrument` außerhalb der Framework-Schnittstelle | hart |
| Obfuskierung | sinnlose Bezeichner in großer Zahl, verschlüsselte String-Konstanten, unerreichbarer Code | hart |
| Netzwerk | `java.net.*`, `java.net.http.*`, Sockets, eingebettete URLs | weich, Zweck muss im Review stehen |
| Dateisystem | Zugriffe außerhalb von `~/Zomboid` und dem Spielordner, Pfade wie `~/.ssh`, Browser-Profile, Steam-Config | weich, außerhalb des Spielordners praktisch immer Ablehnung |
| Reflection | `Class.forName`, `setAccessible` auf Klassen außerhalb von `zombie.*` | weich |
| Serialisierung | `ObjectInputStream` auf Daten von außen | weich |
| Systemzugriffe | Umgebungsvariablen, Systemeigenschaften, Zwischenablage, Tastatur-Hooks | weich |

### Menschliches Review

- Reviewer bekommen den Scanner-Report, den vollständigen Quellcode und bei Updates den Diff zur letzten freigegebenen Version.
- Jedes Review hält fest, welcher Umfang geprüft wurde (vollständig oder nur Diff) und begründet jeden weichen Treffer.
- Eine Freigabe gilt nur, wenn der Reviewer den Scanner-Report-Hash mitsigniert, also bestätigt, ihn gesehen zu haben.
- Checkliste pro Review: Tut die Mod, was die Beschreibung sagt? Gibt es Code ohne erkennbaren Zweck? Werden Daten aus dem Spiel nach außen geschickt? Werden Lua-APIs freigeschaltet, die Server-Cheats ermöglichen?

## Review-Ablauf, Reputation und Flags

Ein Release ist erst freigegeben, wenn zwei unabhängige Reviewer zugestimmt haben, davon mindestens einer mit Stufe „Vertraut“. Jeder kann prüfen und flaggen, aber nur Reputation macht eine Stimme zählbar.

### Einreichung (Pull Request ins Register, vom Tool automatisch erstellt)

1. Autor legt `mod.toml` (nur bei neuer Mod) und `releases/<version>.toml` an und signiert sie.
2. CI prüft Lizenz, Autorenschaft des Quellrepos und Signatur, führt den Doppel-Build und den Scanner aus und hängt die Ergebnisse an den PR.
3. Harter Scanner-Treffer oder abweichende Hashes: PR wird automatisch abgelehnt, mit Begründung.
4. Sonst öffnet sich die Review-Phase. Reviewer committen ihre signierten Review-Dateien in den PR.
5. Bei zwei gültigen Freigaben und ohne offenes kritisches Flag merged ein Maintainer. Der Merge ist Formsache; er prüft nur, ob die Regeln erfüllt sind.

Unabhängigkeit: Autor und Reviewer dürfen nicht dieselbe Person sein. Zwei Reviewer gelten nur als unabhängig, wenn sie nicht von derselben Person eingeladen wurden und nicht zum selben Mod-Team gehören.

| Stufe | Wie man sie erreicht | Gewicht |
|---|---|---|
| Neu | Konto mit verknüpftem GitHub-Account und Schlüssel | Review sichtbar, zählt nicht für die Freigabe |
| Reviewer | 5 Reviews, die von Vertrauten bestätigt wurden, kein berechtigtes Gegen-Flag | zählt als eine Freigabe |
| Vertraut | 25 Reviews, mindestens 6 Monate aktiv, Bestätigung durch zwei Maintainer | zählt als eine Freigabe, Pflicht für jede Freigabe |
| Maintainer | Wahl durch bestehende Maintainer | merged, verwaltet Schlüssel, entscheidet strittige Flags |

Wer eine schädliche Version freigegeben hat, verliert die Stufe und alle seine offenen Freigaben werden neu geprüft. Nutzer können im Launcher zusätzlich festlegen, welchen Reviewern sie persönlich vertrauen.

### Flags

- Jeder Nutzer mit Konto kann ein Release flaggen, mit Beschreibung und optional Codestelle.
- **Kritisch** (Verdacht auf Schadcode): Release verliert sofort seine Freigabe und erscheint im Launcher mit kritischer Warnung, bis zwei Vertraute oder ein Maintainer entscheiden. Lieber falscher Alarm als ein Treffer.
- **Verdacht** und **Info** sperren nicht, erscheinen aber im Launcher und im Review des nächsten Updates.
- **Bestätigter Schadcode:** Release dauerhaft gesperrt, Autorschlüssel widerrufen, Hinweis an alle Nutzer, die die Version installiert haben.
- Missbrauch der Flag-Funktion senkt die Reputation des Melders.

**Updates:** Reviewer prüfen den Diff zur letzten freigegebenen Version. Ab einer bestimmten Größe des Diffs (z. B. mehr als 30 % geänderte Zeilen) gilt das Update als neues Erst-Review.

## Launcher: Verifikation vor dem Laden

Der Launcher ist eine dünne Schicht vor ZombieBuddy: Was die Prüfkette vollständig besteht, lädt er ohne Rückfrage; alles andere nur nach einer Warnung und Bestätigung pro Mod.

### Prüfkette beim Spielstart

1. Register-Index aktualisieren (Git-Pull oder signierter Snapshot). Die Signatur des Index wird gegen den eingebauten Register-Schlüssel geprüft.
2. Für jede aktivierte Java-Mod den SHA-256 der lokalen `.jar` berechnen.
3. Passendes Release im Index suchen. Kein Treffer: Mod gilt als ungeprüft (Warnung und Rückfrage).
4. Autor-, Register- und mindestens zwei Reviewer-Signaturen prüfen, Widerrufsliste der Schlüssel abgleichen.
5. Offene kritische Flags prüfen. Treffer: kritische Warnung, Laden nur nach doppelter Bestätigung.
6. Kompatibilität mit der installierten PZ- und ZombieBuddy-Version prüfen.
7. Erst dann die `.jar` an ZombieBuddy übergeben.

### Verhalten im Alltag

- Downloads nur aus dem Register-Spiegel, nie direkt vom Autor. Die Datei wird vor dem Speichern gegen den Hash geprüft.
- Neue Version verfügbar, aber noch nicht freigegeben: Launcher bleibt bei der letzten freigegebenen Version und bietet die neue als ungeprüft an.
- Offline-Start: letzter signierter Index wird genutzt, aber nur bis zu einem Höchstalter (z. B. 7 Tage), damit eine Sperre nicht dauerhaft umgangen wird.
- Multiplayer: Der Server veröffentlicht die Liste seiner Java-Mods mit Hashes. Der Launcher des Spielers lädt genau diese Versionen aus dem Register und warnt bei Mods, die nicht freigegeben sind.
- Ungeprüfte Mods: Warnung pro Mod mit Name, Hash und Grund (nicht gelistet, Update ungeprüft, Prüfnotiz mit Befunden). Laden nur nach Bestätigung, die Zustimmung gilt nur für diesen Hash. Ausnahme: Hashes mit bestätigtem Schadcode stehen auf einer Sperrliste und werden nie geladen.
- Lua-Mods: lädt das Spiel selbst; der Launcher lässt sie unverändert.

## Optionale Isolierung auf Betriebssystemebene

Die Isolierung ist die letzte Verteidigungslinie für den Fall, dass eine schädliche Version alle Prüfungen passiert. Sie ist optional, weil sie Einrichtung kostet und auf jedem Betriebssystem anders funktioniert.

| System | Ansatz | Schützt vor | Aufwand |
|---|---|---|---|
| Linux | Bubblewrap oder Flatpak-Profil: nur Spielordner, `~/Zomboid`, GPU, Audio und Steam freigegeben | Zugriff auf persönliche Dateien, SSH-Schlüssel, Browser-Daten | gering, gut automatisierbar |
| Linux-Server | Dedicated Server im Docker-Container mit eigenem Nutzer, nur Spielports offen, Netzwerk nach außen gesperrt | Übernahme des Server-Rechners, Weitergabe von Daten | gering |
| Windows | eigenes Standardkonto ohne Adminrechte nur fürs Spielen; alternativ Windows Sandbox zum Testen neuer Mods | Zugriff auf die Dateien des Hauptkontos, Systemänderungen | mittel |
| macOS | eigenes Nutzerkonto fürs Spielen; App-Sandbox-Profile sind für Steam-Spiele kaum praktikabel | Zugriff auf die Dateien des Hauptkontos | mittel |

Zusätzlich, systemunabhängig:

- Ausgehende Verbindungen des Spielprozesses mit einer Firewall auf Steam und die Server des Spielers beschränken (z. B. Little Snitch, OpenSnitch, Windows-Firewall-Regel).
- Der Launcher kann prüfen, ob er isoliert läuft, und das im Startbildschirm anzeigen. Er verlangt Isolierung aber nicht.

Grenze: Isolierung schützt Rechner und Daten, aber nicht den Spielstand oder Multiplayer-Server vor Manipulation. Dafür bleiben Review und Flags zuständig.

## Rechtliches, offene Fragen und MVP

### Rechtliches (keine Rechtsberatung, vor dem Start prüfen lassen)

- Spiegeln und Neubauen von Quellcode nur bei Lizenzen, die das erlauben (z. B. MIT, Apache 2.0, GPL). Die Lizenz ist Pflichtfeld der Einreichung.
- Dekompilieren geschlossener Mods ist nach § 69e UrhG nur eng begrenzt erlaubt, die Weitergabe des dekompilierten Codes nicht. Deshalb enthalten Prüfnotizen weder Datei noch Quellcode.
- Spielklassen von The Indie Stone werden nie verteilt, auch nicht in Teilen.
- The Indie Stone unterstützt Java-Mods offiziell nicht. Das Projekt sollte das deutlich kommunizieren und Fehlerberichte zum Spiel nur ohne Java-Mods weitergeben.
- Haftung: klare Nutzungsbedingungen, dass eine Freigabe eine sorgfältige Prüfung, aber keine Garantie ist.

### Offene Fragen

- Wer betreibt das Register rechtlich (Einzelperson, Verein, bestehende Organisation)?
- Woher kommen die Rechenressourcen für CI und Download-Spiegel, und wer bezahlt sie?
- Bauen wir auf ZombieBuddy auf oder unterstützen wir mehrere Loader? Wie stimmen wir uns mit dessen Autor und seiner eigenen Signaturfunktion ab?
- Wie gewinnen wir die ersten Vertrauten, bevor es eine Reputation gibt?
- Sollen Workshop-Mods mit Java-Anteil automatisch erkannt und mit dem Register abgeglichen werden?

### MVP in vier Schritten

1. **Register-Repo und CLI:** Git-Repo mit dem Datenmodell oben, CLI-Tool `pzreg` zum Signieren von Releases und Reviews und zum Prüfen einer lokalen `.jar` gegen das Register.
2. **CI-Pipeline:** Doppel-Build im gepinnten Container, Hash-Vergleich, Register-Signatur, Scanner mit den harten Regeln. Ergebnisse als Kommentar im PR.
3. **Launcher-Prüfung:** kleine Erweiterung vor ZombieBuddy, die die Prüfkette ausführt und nur freigegebene `.jar` durchlässt.
4. **Community:** Reputationsstufen, Flags mit Sperrwirkung, Diff-Ansicht für Updates, Pilot mit drei bis fünf bestehenden Open-Source-Java-Mods.

## Umsetzungsstand und Abweichungen

Das MVP ist als Projekt BiteCheck im Repo [SmokerDoker/bitecheck-pz](https://github.com/SmokerDoker/bitecheck-pz) umgesetzt (GPL-3.0, ohne fremde Abhängigkeiten, Java 17+). Was von dieser Spezifikation noch fehlt, steht gesammelt in [ROADMAP.md](ROADMAP.md). Gegenüber dieser Spezifikation weicht es an diesen Stellen bewusst ab:

| Thema | Spezifikation | Umgesetzt |
|---|---|---|
| Signaturen | in den TOML-Dateien | als eigene Datei daneben (`1.0.0.toml.sig`), damit die signierte Datei unverändert bleibt; der Build-Eintrag der CI ist eine eigene Datei `1.0.0.build.toml` |
| Daten für den Launcher | Register-Index allgemein | eine kleine, vom Register signierte Datei `index.toml` im Branch `index`, neu erzeugt bei jedem Merge und monatlich; ein schwächerer Zeitstempel-Schlüssel bestätigt täglich, dass sie aktuell ist (höchstens 45 Tage); älter als 7 Tage = nichts mehr automatisch freigeben, Rücksprung auf älteren Index wird abgelehnt |
| Launcher-Anbindung | Prüfschicht vor ZombieBuddy | eigenes Startprogramm, das vor dem Spielstart ZombieBuddys `mod_approvals.json` pro Jar-Hash setzt (geprüft = erlauben, markiert/gesperrt = verbieten, ungeprüft = ZombieBuddy fragt) und das Spiel über Steam startet; ZombieBuddy selbst bleibt unverändert |
| Java-Laufzeit | nicht festgelegt | Startskripte nutzen das Java, das Project Zomboid mitbringt |
| Scanner | ASM/SootUp | eigener Konstantenpool-Leser ohne Abhängigkeiten |
| Einreichung | `pzreg login/submit` | Befehl heißt `bcz`; `login`/`submit` und Mod-Vorlage folgen nach dem ersten Test |
| ZombieBuddy-Mods | nur reproduzierbare Builds | zusätzlich Freigabeart „published“: die vom Autor veröffentlichten Jars zu einem Commit, mit Quellabgleich (jede Klasse muss im Quellcode deklariert sein) und Bytecode-Review, weil diese Mods gegen die nicht weitergebbaren Spiel-Jars kompiliert werden; auf der Website und im Launcher so gekennzeichnet |
| Start aus Steam | nicht festgelegt | Startoption `"…/BiteCheck.bat" --wrap %command% -agentlib:zbNative --`: BiteCheck prüft, startet dann das Spiel unverändert und wartet; bei eigenen Fehlern oder fehlendem Java startet das Spiel trotzdem |
| Dedizierte Server | nicht festgelegt | `--server-dir` und `--wrap` vor dem Serverstart, ZombieBuddy mit `policy=deny-new`; ungeprüfte Mods gibt der Admin einmal per `--allow <Hash>` frei; `--export`/`--compare` gleicht die Mod-Jars von Server und Spielern ab |
| Website | Register-Website | eine statische Seite aus den Registerdateien (`bcz site`), mit Suche nach Name oder Jar-Hash; Veröffentlichung über GitHub Pages |
| Freigabe-Regel | zwei Reviews, eines davon vertrauenswürdig | Start: ein Review von einem vertrauenswürdigen Reviewer, ohne automatische KI-Prüfung; später zwei Reviews (ein Neuling plus ein Reviewer mit höherem Rang), sobald es genug Reviewer gibt |
| Widerruf eines Schlüssels | ab dem Widerrufszeitpunkt verliert nur, was danach signiert wurde | alles, was der Schlüssel je signiert hat, verliert sein Vertrauen: Signaturen tragen keinen vertrauenswürdigen Zeitpunkt, ein gestohlener Schlüssel könnte also auch „alte“ Dateien signieren; betroffene Releases müssen neu signiert und geprüft werden |
| Schlüsselwechsel | neuer Schlüssel, vom alten signiert | `bcz rotate-key`: neuer `keys/authors/<name>.pub` mit Signatur des alten Schlüssels (`.pub.sig`), der alte bleibt in `keys/authors/<name>.previous/` nur für die Releases gültig, die er vor dem Wechsel signiert hatte (Liste `<id>.releases`, vom alten Schlüssel signiert), damit ein gestohlener alter Schlüssel keine neuen Releases signieren kann; ein widerrufener Schlüssel kann keinen Wechsel signieren; der Submitter-Check verlangt dafür sonst keinen Maintainer |
| Harte Scanner-Funde | PR wird automatisch abgelehnt, freigegeben nur ohne harten Fund | die Prüfung im PR schlägt fehl, die CI vermerkt die Funde im signierten Build-Eintrag; freigegeben wird ein solches Release nur mit einer signierten Ausnahme eines vertrauenswürdigen Reviewers (`bcz except`), die im Index sichtbar bleibt (nötig für Mod-Manager wie ZombieBuddy und Jumpstart) |
| Download-Spiegel | Register-Spiegel für alle Downloads | die von der CI gebauten Jars als GitHub-Release im Register-Repo (nur bei Lizenzen, die das erlauben); `bcz fetch` lädt von dort und prüft jeden Hash gegen den Build-Eintrag; der Launcher lädt selbst keine Mods |
| Kritisches Flag | Release verliert sofort seine Freigabe | mit dem nächsten Index, den das Register beim Merge des Flags veröffentlicht: ein ungemergtes Flag würde jedem erlauben, jede Mod abzuschalten; ein Maintainer merged kritische Flags ohne inhaltliche Prüfung |
| Gepinnter Build-Container ohne Netzwerk | Doppel-Build im gepinnten Container, Netzwerk aus | jeder Build läuft auf einer frischen, wegwerfbaren VM ohne Geheimnisse, auf zwei verschiedenen Runner-Images; die Downloads der Mod-Builds sichert die Abhängigkeitsprüfung von Gradle (`verification-metadata.xml` im Template); ein Netzwerk-Verbot würde die meisten Gradle-Builds brechen; dazu freiwillige Nachbauer (`bcz rebuild`) auf eigenen Rechnern |
| Zweiter Build-Anbieter | zweiter Anbieter oder freiwillige Nachbauer | freiwillige Nachbauer (`bcz rebuild`), signierte Nachbau-Einträge im Register, im Index sichtbar |

Erster gemeinsamer Test: Test-Kit mit drei harmlosen Test-Mods (geprüft, markiert, ungeprüft) und eigenem Test-Index. Danach: Registerschlüssel als GitHub-Secret einrichten (Anleitung im Repo unter [docs/SETUP-REGISTRY.md](SETUP-REGISTRY.md)) und erste echte Mods aufnehmen.

## Quellen

- ZombieBuddy – GitHub
- ZombieBuddy – SourceForge (Mod-Signaturen, Sicherheitshinweis)
- Zomboid Storm – GitHub
- Project Zomboid zieht Support-Grenze um Java-Mods – mixed-news
