// Falls das Projekt Packages verwendet: oben z.B. `package de.optimalhelper.csv;`
// ergaenzen und die Datei in den passenden Ordner unter src/main/java legen.

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Liest eine "gedrehte" CSV vom Klassenpfad und schreibt pro Datenspalte eine JSON-Datei.
 *
 * <p>Erwarteter Aufbau - Spalte 1 enthaelt die Attributnamen, jede weitere Spalte
 * ist ein Datensatz:
 *
 * <pre>
 * Name;Server 01;Server 02
 * Hostname (FQDN);srv01.example.org;srv02.example.org
 * IP-Adresse;10.0.0.1;n.v.
 * Rolle;Web;DB
 * </pre>
 *
 * erzeugt {@code Server_01.json} und {@code Server_02.json}.
 *
 * <p>Angewendete Normalisierung:
 * <ul>
 *   <li>Durch den Confluence-Export zerrissene Zeilen werden wieder zusammengesetzt;
 *       Zeilenumbrueche innerhalb von Anfuehrungszeichen bleiben als echte Umbrueche erhalten.</li>
 *   <li>Attributnamen verlieren alle {@code (...)}-Gruppen (auch verschachtelte)
 *       und werden getrimmt.</li>
 *   <li>Werte, die leer sind, {@code n.v.} enthalten oder exakt {@code leer} lauten,
 *       werden komplett weggelassen.</li>
 *   <li>Ab dem Start-Attribut werden Attribute in ein Unterobjekt gruppiert,
 *       bis das Ende-Attribut erreicht ist (siehe Konstanten unten).</li>
 *   <li>Dateinamen: Leerzeichen und pfad-kritische Zeichen werden durch {@code _} ersetzt.</li>
 * </ul>
 *
 * <p>Keine externen Abhaengigkeiten - CSV-Parser und JSON-Ausgabe sind von Hand
 * geschrieben (die Werte sind immer Strings, das bleibt so einfach und korrekt).
 */
public final class CsvZuJsonGenerator {

    /** CSV auf dem Klassenpfad, d.h. src/main/resources/FileRaw.csv */
    private static final String EINGABE_RESSOURCE = "FileRaw.csv";

    /** Ausgabeordner relativ zum resources-Verzeichnis. */
    private static final String AUSGABE_ORDNER = "testfiles";

    /** Moegliche Trennzeichen; das haeufigste in der Kopfzeile gewinnt. */
    private static final char[] TRENNZEICHEN_KANDIDATEN = {';', ',', '\t', '|'};

    /** Text, der an der Stelle eines entfernten Zeilenumbruchs eingefuegt wird. */
    private static final String VERBINDER = "";

    // ---- Konfiguration der Gruppierung --------------------------------------

    /**
     * Attribut, das die Gruppe oeffnet (Vergleich mit dem BEREINIGTEN Namen,
     * also ohne "(...)"-Teil). Es bleibt selbst auf oberster Ebene.
     */
    private static final String GRUPPEN_START_ATTRIBUT = "Conditional";

    /**
     * Wert, den das Start-Attribut haben muss, damit gruppiert wird.
     * {@code null} = immer gruppieren. {@code "N"} = nur bei Wert N gruppieren.
     */
    private static final String GRUPPEN_START_WERT = null;

    /**
     * Bei diesem Attribut endet die Gruppe; es liegt selbst wieder auf oberster
     * Ebene. {@code null} = bis zum Ende des Datensatzes gruppieren.
     */
    private static final String GRUPPEN_ENDE_ATTRIBUT = "GroupEnd";

    /** Name des verschachtelten JSON-Objekts. */
    private static final String GRUPPEN_NAME = "customGroup";

    // ---- Excel-Seriendatum ---------------------------------------------------

    /** Excel-Zahlen in wissenschaftlicher Schreibweise als Datum ausgeben. */
    private static final boolean EXCEL_DATUM_ERKENNEN = true;

    /**
     * Excel-Tag 1 ist der 01.01.1900. Wegen des Schaltjahr-Fehlers von 1900
     * (Excel kennt einen 29.02.1900, den es nie gab) ist die Rechenbasis der 30.12.1899.
     */
    private static final LocalDate EXCEL_EPOCHE = LocalDate.of(1899, 12, 30);

    /** Plausibler Bereich: 31.12.1899 bis 31.12.2999. */
    private static final double SERIAL_MIN = 1;
    private static final double SERIAL_MAX = 401768;

    // ---- regulaere Ausdruecke ------------------------------------------------

    private static final Pattern INNERSTE_KLAMMERN = Pattern.compile("\\([^()]*\\)");
    private static final Pattern OFFENE_KLAMMER = Pattern.compile("\\([^)]*$");
    private static final Pattern MEHRFACH_LEERRAUM = Pattern.compile("\\s+");
    private static final Pattern NICHT_VORHANDEN = Pattern.compile("n\\.\\s*v\\.", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEER = Pattern.compile("^leer$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERBOTENE_DATEINAMENSZEICHEN = Pattern.compile("[\\\\/:*?\"<>|]");
    private static final Pattern WISSENSCHAFTLICHE_ZAHL =
            Pattern.compile("^[+-]?\\d+([.,]\\d+)?[eE][+-]?\\d+$");
    private static final DateTimeFormatter DATUM_AUSGABE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DATUM_ZEIT_AUSGABE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    private CsvZuJsonGenerator() {
    }

    public static void main(String[] args) throws IOException {
        Path ausgabeOrdner = ermittleAusgabeordner();
        List<Path> geschrieben = erzeuge(ausgabeOrdner);

        System.out.println(geschrieben.size() + " Datei(en) geschrieben nach " + ausgabeOrdner.toAbsolutePath());
        for (Path datei : geschrieben) {
            System.out.println("  " + datei.getFileName());
        }
    }

    // ------------------------------------------------------------------
    // Hauptablauf
    // ------------------------------------------------------------------

    /** Liest die CSV-Ressource und schreibt pro Datenspalte eine JSON-Datei. */
    public static List<Path> erzeuge(Path ausgabeOrdner) throws IOException {
        List<String> zeilen = leseZeilen(EINGABE_RESSOURCE);
        if (zeilen.size() < 2) {
            throw new IOException(EINGABE_RESSOURCE + " hat weniger als zwei nicht-leere Zeilen.");
        }

        char trenner = ermittleTrennzeichen(zeilen.get(0));
        List<List<String>> tabelle = new ArrayList<>();
        for (String datensatz : repariereZeilenumbrueche(zeilen, trenner)) {
            tabelle.add(zerlege(datensatz, trenner));
        }

        List<String> kopf = tabelle.get(0);
        if (kopf.size() < 2) {
            throw new IOException("Kopfzeile ergab mit Trennzeichen '" + trenner
                    + "' nur eine Spalte. Kopfzeile war: " + zeilen.get(0));
        }

        Files.createDirectories(ausgabeOrdner);

        List<Path> geschrieben = new ArrayList<>();
        Map<String, String> vergebeneNamen = new LinkedHashMap<>();

        // Spalte 0 enthaelt die Attributnamen, jede weitere Spalte einen Datensatz.
        for (int spalte = 1; spalte < kopf.size(); spalte++) {
            Map<String, Object> daten = baueDatensatz(tabelle, kopf, spalte);
            Path ziel = ausgabeOrdner.resolve(freierDateiname(kopf.get(spalte), vergebeneNamen));
            Files.write(ziel, zuJson(daten).getBytes(StandardCharsets.UTF_8));
            geschrieben.add(ziel);
        }
        return geschrieben;
    }

    /** Baut das (ggf. gruppierte) Schluessel-Wert-Objekt fuer eine Datenspalte. */
    private static Map<String, Object> baueDatensatz(List<List<String>> tabelle, List<String> kopf, int spalte) {
        Map<String, Object> daten = new LinkedHashMap<>();
        Map<String, Object> gruppe = new LinkedHashMap<>();
        boolean inGruppe = false;

        for (int zeile = 1; zeile < tabelle.size(); zeile++) {
            List<String> felder = tabelle.get(zeile);

            String attribut = bereinigeAttributname(feld(felder, 0));
            if (attribut.isEmpty()) {
                continue; // Leer-/Trennzeilen ueberspringen
            }

            String wert = feld(felder, spalte);

            // Das Ende-Attribut schliesst die Gruppe und liegt selbst wieder oben.
            if (inGruppe && attribut.equals(GRUPPEN_ENDE_ATTRIBUT)) {
                inGruppe = false;
            }

            boolean istStart = attribut.equals(GRUPPEN_START_ATTRIBUT);

            // Auf dem Rohwert entscheiden, bevor die Weglass-Regeln greifen koennen.
            boolean oeffnetGruppe = istStart
                    && (GRUPPEN_START_WERT == null || GRUPPEN_START_WERT.equalsIgnoreCase(wert.trim()));

            if (!istWegzulassen(wert)) {
                // Das Start-Attribut bleibt oben; gruppiert wird erst danach.
                Map<String, Object> ziel = (inGruppe && !istStart) ? gruppe : daten;
                if (ziel.put(attribut, excelDatum(wert.trim())) != null) {
                    System.err.println("Warnung: Attribut '" + attribut + "' kommt in Spalte '"
                            + kopf.get(spalte) + "' mehrfach vor - der letzte Wert gewinnt.");
                }
            }

            if (oeffnetGruppe) {
                inGruppe = true;
                // Platz der Gruppe hier reservieren, damit sie in Originalreihenfolge steht.
                daten.put(GRUPPEN_NAME, gruppe);
            }
        }

        if (gruppe.isEmpty()) {
            daten.remove(GRUPPEN_NAME);
        }
        return daten;
    }

    // ------------------------------------------------------------------
    // Einlesen und Pfade
    // ------------------------------------------------------------------

    /** Liest die Ressource als UTF-8 (ohne BOM) und liefert die Zeilen ohne End-Leerzeilen. */
    private static List<String> leseZeilen(String name) throws IOException {
        String text;
        try (InputStream eingabe = CsvZuJsonGenerator.class.getClassLoader().getResourceAsStream(name)) {
            if (eingabe == null) {
                throw new IOException("Ressource '" + name + "' nicht auf dem Klassenpfad gefunden. "
                        + "Erwartet unter src/main/resources/" + name);
            }
            text = new String(eingabe.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1); // BOM des Exports entfernen
        }

        List<String> zeilen = new ArrayList<>(List.of(text.split("\r?\n", -1)));
        // Nur Leerzeilen am Ende entfernen - Leerzeilen mittendrin koennen zu einem
        // zerrissenen Datensatz gehoeren und werden beim Reparieren behandelt.
        while (!zeilen.isEmpty() && zeilen.get(zeilen.size() - 1).trim().isEmpty()) {
            zeilen.remove(zeilen.size() - 1);
        }
        return zeilen;
    }

    /**
     * Bevorzugt den Quellbaum (src/main/resources/testfiles), damit die erzeugten
     * Dateien neben der CSV liegen und einen Clean-Build ueberleben; sonst neben
     * der CSV auf dem Klassenpfad. Uebersteuerbar mit {@code -Dcsv2json.out=/pfad}.
     */
    private static Path ermittleAusgabeordner() {
        String uebersteuert = System.getProperty("csv2json.out");
        if (uebersteuert != null && !uebersteuert.isEmpty()) {
            return Paths.get(uebersteuert);
        }

        Path quellRessourcen = Paths.get("src", "main", "resources");
        if (Files.isDirectory(quellRessourcen)) {
            return quellRessourcen.resolve(AUSGABE_ORDNER);
        }

        var ressource = CsvZuJsonGenerator.class.getClassLoader().getResource(EINGABE_RESSOURCE);
        if (ressource != null && "file".equals(ressource.getProtocol())) {
            try {
                return Paths.get(ressource.toURI()).getParent().resolve(AUSGABE_ORDNER);
            } catch (java.net.URISyntaxException ignoriert) {
                // dann eben der letzte Ausweg unten
            }
        }
        return Paths.get(AUSGABE_ORDNER);
    }

    // ------------------------------------------------------------------
    // CSV-Normalisierung
    // ------------------------------------------------------------------

    private static char ermittleTrennzeichen(String kopfzeile) throws IOException {
        char bestes = 0;
        long besteAnzahl = 0;
        for (char kandidat : TRENNZEICHEN_KANDIDATEN) {
            long anzahl = kopfzeile.chars().filter(c -> c == kandidat).count();
            if (anzahl > besteAnzahl) {
                besteAnzahl = anzahl;
                bestes = kandidat;
            }
        }
        if (besteAnzahl == 0) {
            throw new IOException("Kein Trennzeichen in der Kopfzeile erkennbar: " + kopfzeile);
        }
        return bestes;
    }

    /**
     * Setzt Datensaetze, die ueber mehrere physische Zeilen zerrissen sind, wieder
     * zusammen: Zeilen werden angehaengt, bis der Datensatz so viele Felder hat wie
     * die Kopfzeile und nicht in einem offenen Anfuehrungszeichen endet.
     */
    private static List<String> repariereZeilenumbrueche(List<String> zeilen, char trenner) {
        int erwarteteFelder = zerlege(zeilen.get(0), trenner).size();

        List<String> datensaetze = new ArrayList<>();
        StringBuilder puffer = null;
        int reparaturen = 0;

        for (String zeile : zeilen) {
            if (puffer == null) {
                if (zeile.trim().isEmpty()) {
                    continue;
                }
                puffer = new StringBuilder(zeile);
            } else {
                // Umbruch in einem offenen Anfuehrungszeichen ist echter Inhalt und
                // bleibt erhalten; Umbruch in einem unfertigen Datensatz ist
                // Export-Schaden und wird entfernt.
                puffer.append(endetInAnfuehrung(puffer) ? "\n" : VERBINDER).append(zeile);
                reparaturen++;
            }

            if (!endetInAnfuehrung(puffer)) {
                int felder = zerlege(puffer.toString(), trenner).size();
                if (felder >= erwarteteFelder) {
                    if (felder > erwarteteFelder) {
                        System.err.println("Warnung: Datensatz hat " + felder + " Felder, erwartet "
                                + erwarteteFelder + ": " + vorschau(puffer));
                    }
                    datensaetze.add(puffer.toString());
                    puffer = null;
                }
            }
        }

        if (puffer != null) {
            System.err.println("Warnung: letzter Datensatz ist unvollstaendig: " + vorschau(puffer));
            datensaetze.add(puffer.toString());
        }
        if (reparaturen > 0) {
            System.out.println(reparaturen + " verirrte(r) Zeilenumbruch/-brueche repariert.");
        }
        return datensaetze;
    }

    /** Endet der Text mitten in einem Anfuehrungszeichen-Feld? (beachtet ""-Escapes) */
    private static boolean endetInAnfuehrung(CharSequence text) {
        boolean inAnfuehrung = false;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '"') {
                inAnfuehrung = !inAnfuehrung;
            }
        }
        // Ein "" zaehlt doppelt und hebt sich damit von selbst auf.
        return inAnfuehrung;
    }

    /** Zerlegt einen vollstaendigen Datensatz in Felder (beachtet Anfuehrungszeichen und ""-Escapes). */
    private static List<String> zerlege(String datensatz, char trenner) {
        List<String> felder = new ArrayList<>();
        StringBuilder aktuell = new StringBuilder();
        boolean inAnfuehrung = false;

        for (int i = 0; i < datensatz.length(); i++) {
            char zeichen = datensatz.charAt(i);
            if (inAnfuehrung) {
                if (zeichen == '"') {
                    if (i + 1 < datensatz.length() && datensatz.charAt(i + 1) == '"') {
                        aktuell.append('"');
                        i++;
                    } else {
                        inAnfuehrung = false;
                    }
                } else {
                    aktuell.append(zeichen);
                }
            } else if (zeichen == '"') {
                inAnfuehrung = true;
            } else if (zeichen == trenner) {
                felder.add(aktuell.toString());
                aktuell.setLength(0);
            } else {
                aktuell.append(zeichen);
            }
        }
        felder.add(aktuell.toString());
        return felder;
    }

    private static String feld(List<String> felder, int index) {
        return index < felder.size() ? felder.get(index) : "";
    }

    private static String vorschau(CharSequence text) {
        return text.length() <= 40 ? text.toString() : text.subSequence(0, 40) + "...";
    }

    // ------------------------------------------------------------------
    // Attribut-/Wert-Normalisierung
    // ------------------------------------------------------------------

    /** Entfernt "(...)"-Gruppen (auch verschachtelte), buendelt Leerraum, trimmt. */
    static String bereinigeAttributname(String name) {
        String ergebnis = name, vorher;
        do {
            vorher = ergebnis;
            ergebnis = INNERSTE_KLAMMERN.matcher(ergebnis).replaceAll(" ");
        } while (!ergebnis.equals(vorher));

        ergebnis = OFFENE_KLAMMER.matcher(ergebnis).replaceAll(" ");
        return MEHRFACH_LEERRAUM.matcher(ergebnis).replaceAll(" ").trim();
    }

    /**
     * Wandelt eine Excel-Seriennummer in wissenschaftlicher Schreibweise
     * (z.B. "4,6234E+04", wie Excel sie in zu schmalen Spalten exportiert) in ein
     * lesbares Datum. Alles andere - auch schlichte Ganzzahlen - bleibt unangetastet,
     * damit echte Messwerte nicht versehentlich zu Datumsangaben werden.
     */
    static String excelDatum(String wert) {
        if (!EXCEL_DATUM_ERKENNEN || wert == null || !WISSENSCHAFTLICHE_ZAHL.matcher(wert).matches()) {
            return wert;
        }

        double serial;
        try {
            serial = new BigDecimal(wert.replace(',', '.')).doubleValue();
        } catch (NumberFormatException e) {
            return wert;
        }

        if (!(serial >= SERIAL_MIN && serial <= SERIAL_MAX)) {
            System.err.println("Warnung: '" + wert + "' sieht nach einer Excel-Zahl aus, liegt aber "
                    + "ausserhalb des Datumsbereichs - unveraendert uebernommen.");
            return wert;
        }

        long tage = (long) Math.floor(serial);
        double bruchteil = serial - tage;
        LocalDate datum = EXCEL_EPOCHE.plusDays(tage);

        if (bruchteil < 1e-9) {
            return datum.format(DATUM_AUSGABE);   // reines Datum
        }
        long sekunden = Math.min(Math.round(bruchteil * 86400), 86399);
        return LocalDateTime.of(datum, LocalTime.ofSecondOfDay(sekunden)).format(DATUM_ZEIT_AUSGABE);
    }

    /** Wahr, wenn der Wert gar nicht erst ins JSON geschrieben werden soll. */
    static boolean istWegzulassen(String wert) {
        if (wert == null || wert.trim().isEmpty()) {
            return true;
        }
        String getrimmt = wert.trim();
        return NICHT_VORHANDEN.matcher(getrimmt).find() || LEER.matcher(getrimmt).matches();
    }

    /**
     * Dateiname aus dem Spaltenkopf: Leerraum und pfad-kritische Zeichen werden zu '_'.
     * Die "(...)"-Teile bleiben absichtlich erhalten - sie unterscheiden oft zwei
     * Spalten, ohne sie wuerden deren Dateien kollidieren.
     * Kollidiert der Name trotzdem, wird _2, _3, ... angehaengt statt zu ueberschreiben.
     */
    private static String freierDateiname(String spaltenkopf, Map<String, String> vergeben) {
        String basis = spaltenkopf.trim();
        basis = VERBOTENE_DATEINAMENSZEICHEN.matcher(basis).replaceAll("_");
        basis = MEHRFACH_LEERRAUM.matcher(basis).replaceAll("_");
        if (basis.isEmpty()) {
            basis = "unbenannt";
        }

        String dateiname = basis + ".json";
        if (vergeben.containsKey(dateiname)) {
            int lfdNr = 2;
            while (vergeben.containsKey(basis + "_" + lfdNr + ".json")) {
                lfdNr++;
            }
            System.err.println("Warnung: Spalten '" + vergeben.get(dateiname) + "' und '" + spaltenkopf
                    + "' ergeben denselben Dateinamen - die zweite wird als "
                    + basis + "_" + lfdNr + ".json geschrieben.");
            dateiname = basis + "_" + lfdNr + ".json";
        }
        vergeben.put(dateiname, spaltenkopf);
        return dateiname;
    }

    // ------------------------------------------------------------------
    // JSON-Ausgabe
    // ------------------------------------------------------------------

    private static String zuJson(Map<String, Object> daten) {
        StringBuilder sb = new StringBuilder();
        schreibeObjekt(sb, daten, 1);
        return sb.append('\n').toString();
    }

    /** Schreibt ein JSON-Objekt, dessen Werte Strings oder verschachtelte Maps sind. */
    @SuppressWarnings("unchecked")
    private static void schreibeObjekt(StringBuilder sb, Map<String, Object> objekt, int tiefe) {
        sb.append("{\n");
        int i = 0;
        for (Map.Entry<String, Object> eintrag : objekt.entrySet()) {
            sb.append("  ".repeat(tiefe)).append('"').append(maskiere(eintrag.getKey())).append("\": ");

            if (eintrag.getValue() instanceof Map) {
                schreibeObjekt(sb, (Map<String, Object>) eintrag.getValue(), tiefe + 1);
            } else {
                sb.append('"').append(maskiere(String.valueOf(eintrag.getValue()))).append('"');
            }
            sb.append(++i < objekt.size() ? ",\n" : "\n");
        }
        sb.append("  ".repeat(tiefe - 1)).append('}');
    }

    /** Maskiert einen String fuer die JSON-Ausgabe. */
    private static String maskiere(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char zeichen = text.charAt(i);
            switch (zeichen) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (zeichen < 0x20) {
                        sb.append(String.format("\\u%04x", (int) zeichen));
                    } else {
                        sb.append(zeichen);
                    }
            }
        }
        return sb.toString();
    }
}
