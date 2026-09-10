# Plan for chatprogrammet

Planlagt 10. september 2026 ud fra projektformuleringen. Projektet udføres af én person i Java og IntelliJ. Den valgte, obligatoriske udvidelse er en grafisk klient med JavaFX.

Dette dokument beskriver den oprindelige løsning og arbejdsrækkefølge. Implementeringen af server, konsolklient og JavaFX-klient er nu gennemført. Se README og docs/TESTS.md for opstart, faktisk teststatus og den resterende personlige generalprøve.

## 1. Det færdige program

Projektet består af én server, én konsolklient og én JavaFX-klient. Begge klienttyper kan være tilsluttet samme server og chatte med hinanden. Konsolklienten skal afleveres, da den indgår i minimumskravene.

Brugerens forløb:

1. Start serveren. Start derefter en eller flere klienter.
2. Forbind til en vært og port; lokalt bruger vi som standard `localhost:5555`.
3. Vælg et unikt brugernavn. Ved optaget navn vises en fejl, og brugeren kan prøve igen på samme forbindelse.
4. Efter serverens loginbekræftelse placeres brugeren i `lobby`.
5. Almindelig tekst sendes til alle i brugerens aktuelle rum, inklusive afsenderen.
6. Brugeren kan skifte til de forudoprettede rum `java` og `hygge`.
7. En privat besked kan sendes til en onlinebruger uanset vedkommendes rum. Kun modtageren modtager selve privatbeskeden.
8. Ved afslutning eller registreret forbindelsesbrud fjernes forbindelsen fra serverens samlinger, og brugernavnet bliver ledigt igen.

Første versions data findes i hukommelsen. Login er valg af navn for den aktuelle forbindelse. JavaFX er projektets udvidelse; vedvarende historik, adgangskoder og filoverførsel ligger uden for denne plan.

## 2. Teknologi og IntelliJ

| Valg | Formål |
| --- | --- |
| Java 21 | Fælles sprog- og runtimeversion; allerede installeret på computeren. |
| Maven | Samler afhængigheder, kompilering og test i ét projekt. |
| JavaFX 21 | Grafisk klient. Den konkrete patchversion fastlåses i `pom.xml` ved opsætning. |
| JUnit Jupiter | Automatiske tests af parser, serverlogik og centrale samtidighedsregler. |
| Java-standardbiblioteket | `Socket`, `ServerSocket`, `ExecutorService` og tekststrømme. |
| Git og GitHub | Små commits efter fungerende trin og det endelige repository. |

Vi bruger ét Maven-modul med pakker til server, fælles protokol og klienter. JavaFX hentes som Maven-afhængigheder. OpenJFX beskriver opsætning med både Maven og IntelliJ i sin [officielle vejledning](https://openjfx.io/openjfx-docs/).

IntelliJs Project SDK og Maven Runner sættes til Java 21. Computerens aktuelle standard-JDK er Java 25, så projektets version skal vælges eksplicit. Under udviklingen opretter vi kørselskonfigurationer til server, konsolklient og JavaFX-klient samt mulighed for at starte flere klientprocesser.

## 3. Konsol og grafisk brugerflade

Konsolbrugeren skal kunne skrive almindelig tekst og få hjælp med `/help`.

| Input | Handling |
| --- | --- |
| `Hej alle` | Send til det aktuelle rum. |
| `/join java` | Bed serveren om at skifte til `java`. |
| `/msg alice Hej Alice` | Send en privat besked til `alice`. |
| `/help` | Vis kommandoerne og de tilgængelige rum lokalt. |
| `/quit` | Afslut forbindelsen. |

JavaFX får to enkle visninger i samme vindue:

- **Login:** serveradresse, port, brugernavn, forbind-knap og synlig fejltekst. Under forbindelsesforsøget vises en ventetilstand.
- **Chat:** eget brugernavn og forbindelsesstatus øverst, vælger til de tre faste rum, en beskedliste samt tekstfelt og send-knap. Et separat modtagerfelt og en knap sender private beskeder. Enter sender til det aktuelle rum. En afbryd-knap afslutter forbindelsen.

Beskeder mærkes med tid, afsender og rum; private beskeder får en tydelig privatmarkering. Modtagne beskeder vises fra serverens data. Afsenderens rumbesked vises, når den kommer tilbage fra serveren, så den ikke vises to gange. En privat afsender får en særskilt statusbekræftelse.

Loginvisningen skifter først til chat efter `LOGIN_OK`. Det aktive rum ændres først efter `ROOM_JOINED`. Mens login eller rumskifte afventer svar, kan brugeren ikke sende nye chatkommandoer. Ved fejl bliver brugeren i sin hidtidige tilstand og kan rette input.

Ved mistet forbindelse deaktiveres sendefelterne, og brugeren får en tydelig besked og mulighed for selv at forbinde igen. Lukning af vinduet skal lukke socket og klientens baggrundsarbejde. Fejl skal kunne forstås i brugerfladen uden at læse en stacktrace.

## 4. Klasseansvar og genbrug

| Pakke | Klasse | Ansvar |
| --- | --- | --- |
| `chat.server` | `ChatServer` | Starter `ServerSocket`, accepterer forbindelser, ejer trådpuljen og lukker serverens ressourcer. |
| `chat.server` | `ClientHandler` | Læser fra én klient, afleverer kommandoer til serverlogikken og håndterer sikker skrivning og oprydning for forbindelsen. |
| `chat.server` | `ChatService` | Udfører login, rumskifte, broadcast og privatbeskeder samt danner svar og fejl. |
| `chat.server` | `ChatState` | Ejer brugerregistre, rum og medlemskaber og beskytter alle opslag og ændringer med én fælles lås. |
| `chat.protocol` | `ClientMessage`, `ServerMessage` | Små, uforanderlige beskedobjekter, der passer til de to forskellige protokolformater. |
| `chat.protocol` | `MessageParser` | Parser og opbygger protokollinjer samt kontrollerer deres struktur. |
| `chat.client` | `ChatConnection` | Fælles forbindelses- og afsendelseskode, som bruges af begge klienter. |
| `chat.client` | `ServerListener` | Modtager og parser serverbeskeder i en separat tråd og giver dem videre til klienten. |
| `chat.client.console` | `ChatClient` | Konsolinput, kommandooversættelse og udskrift. |
| `chat.client.gui` | `ChatApplication` | Starter JavaFX og forbinder brugerfladens handlinger med `ChatConnection`. |

Klasserne tilføjes efter behov i de enkelte trin. GUI-visninger kan opdeles i små klasser, hvis det gør koden lettere at læse. Første udgave bygges med JavaFX-layoutklasser og en lille CSS-fil.

Netværkskoden i `ChatConnection` kender ikke JavaFX-kontroller eller konsoludskrift. Den leverer beskeder og forbindelseshændelser gennem et lille callback-interface. Dermed bruger begge klienter samme protokol og samme håndtering af forbindelsen.

`ChatService` kan testes med testmodtagere, som opsamler beskeder i hukommelsen. Den kræver dermed ikke rigtige sockets i hver logiktest. `ChatState` samler opgavens foreslåede `ClientRegistry` og `ChatRoomManager`, så de beslægtede data kan ændres samlet.

## 5. Protokollen

Vi følger opgavens linjebaserede format og bruger UTF-8. Én afsluttende linjeskiftmarkør afgrænser hver besked; TCP læses som en strøm, ikke som én komplet besked pr. netværkslæsning.

Klient til server: `TYPE|TARGET|PAYLOAD`.

| Type | Target | Payload | Regel |
| --- | --- | --- | --- |
| `LOGIN` | Tom | Ønsket brugernavn | Kun før et vellykket login. |
| `JOIN_ROOM` | Rum | Tom | Rummet skal eksistere. |
| `TEXT` | Aktuelt rum | Beskedtekst | Serveren kontrollerer medlemskabet. |
| `PRIVATE` | Brugernavn | Beskedtekst | Modtageren skal være online. |
| `QUIT` | Tom | Tom | Tilladt både før og efter login. |

Server til klient: `TIMESTAMP|TYPE|SENDER|TARGET|PAYLOAD`.

| Type | Sender | Target | Payload og betydning |
| --- | --- | --- | --- |
| `LOGIN_OK` | `server` | Startrum | Godkendt brugernavn; åbner chatten. |
| `ROOM_JOINED` | `server` | Nyt rum | Kort bekræftelse; opdaterer klientens aktive rum. |
| `TEXT` | Afsenderens godkendte navn | Rum | Rumbesked til rummets medlemmer, inklusive afsender. |
| `PRIVATE` | Afsenderens godkendte navn | Modtagernavn | Privat tekst; sendes kun til modtageren. |
| `INFO` | `server` | Brugernavn | Status, fx bekræftelse efter afsendelse af en privat besked. |
| `ERROR` | `server` | Brugernavn eller tom før login | Tydelig fejltekst til den berørte klient. |
| `BYE` | `server` | Brugernavn eller tom før login | Bekræfter normal afslutning før lukning. |

Eksempel: `LOGIN||bob` besvares med `2026-09-10 15:00:00|LOGIN_OK|server|lobby|bob`.

Serveren bestemmer tidspunkt og afsender. Tidspunktet formateres som `yyyy-MM-dd HH:mm:ss` i serverens dokumenterede lokale tidszone. Efter login identificerer serveren klienten via forbindelsen. Et brugernavn i en beskeds tekst kan aldrig ændre dens afsender.

Faste regler for første version:

- Brugernavne normaliseres til små bogstaver med `Locale.ROOT` og består af 1–20 tegn fra `a-z`, `0-9` og `_`. `server` er reserveret. `Bob` og `bob` konkurrerer om samme navn.
- Rum hedder `lobby`, `java` og `hygge`. Ukendte rum giver fejl; det gamle medlemskab bevares. Et skift til det nuværende rum bekræftes uden at ændre medlemskabet.
- Før login accepteres kun `LOGIN` og `QUIT`. Et nyt `LOGIN` efter vellykket login giver fejl.
- Tom eller blank beskedtekst afvises. Beskeder er på én linje; linjeskift i payload tillades ikke. Danske tegn og `|` i beskedteksten skal fungere.
- En ikkeeksisterende privat modtager giver fejl til afsenderen. `INFO` efter afsendelse betyder ikke, at modtageren har læst beskeden.
- Forkert antal felter, ukendt type og felter med ulovligt indhold giver `ERROR`. Klienten kan derefter sende en ny gyldig kommando på samme forbindelse.

Parseren bruger opgavens `split("\\|", 3)` for klientlinjer og `split("\\|", 5)` for serverlinjer. En positiv grænse bevarer resten i sidste felt, så eksempelvis `Hej | verden` er en gyldig payload. Tomme slutfelter i `QUIT||` skal også bevares og testes. Se [Java-dokumentationen for String.split](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/String.html#split(java.lang.String,int)).

## 6. Tråde, delte data og afbrydelser

**Serveren:** En accept-løkke tager imod forbindelser. Hver forbindelse får én `ClientHandler`, som kører i en `ExecutorService`. Vi planlægger en fast pulje på 10 handlertråde og en tilsvarende kapacitetsgrænse. Forbindelser over grænsen får en fejl og lukkes.

Kapacitetskontrollen omfatter også forbindelser, der endnu ikke er logget ind. En almindelig fast trådpulje har ellers en ubegrænset opgavekø, så en ekstra klient kunne vente på ubestemt tid på en handler. Den begrænsning håndteres eksplicit ved accept. Se [Java-dokumentationen for Executors](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Executors.html#newFixedThreadPool(int)).

**Konsolklienten:** Brugerinput og modtagelse er adskilt. `ServerListener` læser fra serveren, mens konsoltråden venter på tastaturet.

**JavaFX-klienten:** Forbindelse og afsendelse udføres i baggrunden. Modtagelse har sin egen lyttertråd og må ikke optage den eneste tråd, der også skal sende. GUI-opdateringer sendes til JavaFX-tråden med `Platform.runLater`, som beskrevet i [OpenJFX-dokumentationen](https://openjfx.io/javadoc/21/javafx.graphics/javafx/application/Platform.html#runLater(java.lang.Runnable)). Klientens baggrundsressourcer lukkes ved afbrydelse og vindueslukning.

**Fælles serverdata:** `ChatState` indeholder almindelige maps og sets, der er private. Alle læsninger og ændringer går gennem metoder, som bruger samme lås. Ingen metode udleverer de mutable samlinger. Dette er projektets valgte måde at gøre registrene trådsikre på.

Det giver følgende regler:

1. Kontrol og registrering af et brugernavn sker i samme låste operation. To samtidige loginforsøg kan ikke begge få navnet.
2. Rumskifte valideres først og ændrer gammelt medlemskab, nyt medlemskab og aktuelt rum samlet under låsen.
3. Modtagerne for en besked kopieres under låsen. Selve netværksskrivningen sker bagefter, så en langsom forbindelse ikke holder låsen til alle serverdata.
4. Hver klientforbindelse har en separat sendelås. En hel protokollinje inklusive linjeskift og flush skrives samlet, så samtidige afsendere ikke blander deres bytes.
5. Låsene holdes adskilt: ingen socket-skrivning under tilstandslåsen, og oprydning efter en skrivefejl udføres efter frigivelse af sendelåsen.

En `ConcurrentHashMap` er et muligt alternativ, men gør ikke flere operationer på tværs af bruger- og rumregistre til én samlet operation. Derfor vælger vi den fælles lås til dette lille projekt. Java beskriver forskellen mellem trådsikre enkeltoperationer og sammensatte operationer i [dokumentationen for ConcurrentHashMap](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ConcurrentHashMap.html).

Modtagerkopien definerer medlemskab på det tidspunkt, serveren accepterer beskeden. En allerede afsendt besked fra det gamle rum kan derfor ankomme under et rumskifte; beskedens `TARGET` viser altid, hvilket rum den tilhører. Der loves ikke én fælles rækkefølge for alle samtidige afsendere.

**Oprydning:** `QUIT`, EOF fra læsning og I/O-fejl ender i samme oprydningsforløb. Et `finally`-afsnit sikrer, at det også køres, når en handler fejler. Forløbet må kunne kaldes flere gange uden at frigive kapacitet flere gange eller fjerne en nyere forbindelse med samme brugernavn. Det fjerner den konkrete forbindelse fra registrene, lukker dens socket og frigiver dens plads.

Serverstop lukker også forbindelser, der endnu ikke er logget ind, lukker `ServerSocket` og afslutter trådpuljen. Klienterne skal reagere på serverstop og frigive deres egne ressourcer. Uventet afbrydelse testes ved at stoppe en klientproces; registrering sker, når socket-læsning eller -skrivning opdager bruddet.

## 7. Udviklingsrækkefølge og færdigkriterier

Hvert trin skal kunne forklares og demonstreres, før det næste begynder. Lav et lille commit efter hvert fungerende trin, og før løbende testnoter og AI-noter.

| Trin | Arbejde | Færdigt når |
| --- | --- | --- |
| 1 | Maven, Git, Java 21 og første forbindelse | Server og én konsolklient starter fra IntelliJ, og én tekstlinje sendes og besvares. |
| 2 | Protokolobjekter, parser og validering | Gyldige beskeder, fejlformater, tomme slutfelter og `|` i payload dækkes af JUnit. |
| 3 | Trådpulje, login og fællesrum | Tre konsolklienter kan sende og modtage samtidig; optaget navn afvises; afsenderen vises korrekt. |
| 4 | Rum og private beskeder | Rum er isoleret, private beskeder når kun modtageren, og fejl efterlader gyldig tilstand. |
| 5 | Oprydning og samlet minimumstest | Normal afslutning, processtop, serverstop og fejlformater håndteres; parser og central logik har automatiske tests. |
| 6 | JavaFX oven på den fælles klientkode | Login, rumskifte, almindelige og private beskeder fungerer; konsol og GUI kan chatte sammen uden at GUI fryser. |
| 7 | Aflevering og demonstration | README, diagrammer, faktiske testresultater og AI-dokumentation er færdige; en ren kopi kan startes ud fra vejledningen. |

Koden skrives i små dele med en kort forklaring af ansvar, dataflow og tråde. Efter hver del læses den igennem, afprøves og justeres. Det giver samtidig materiale til at forklare og vurdere AI-forslag ved præsentationen.

## 8. Testplan

Automatiske tests placeres under `src/test/java` og køres gennem Maven og IntelliJ.

| Område | Konkrete tests |
| --- | --- |
| Parser | Alle typer i begge retninger; for få felter; ukendt type; tomme obligatoriske felter; `QUIT||`; danske tegn; `|` i payload; opbygning og genlæsning af en besked. |
| Login | Vellykket login; optaget navn; forskel på store/små bogstaver; ugyldigt navn; nyt forsøg efter afvisning; kommandoer før login; gentaget login. |
| Rum | Broadcast når kun medlemmer; afsender får beskeden én gang; ugyldigt skift bevarer gammelt rum; `TEXT` til et andet rum afvises. |
| Privat | Kun modtageren får `PRIVATE`; afsender får særskilt status; offline modtager giver fejl; kommunikation mellem forskellige rum. |
| Samtidighed | To samtidige registreringer af samme navn giver præcis én vinder; gentagen oprydning er harmløs; en gammel handler kan ikke fjerne en ny session. |
| Fejl og livsforløb | En fejlformateret linje efterfulgt af en gyldig kommando virker på samme forbindelse; EOF og I/O-fejl udløser oprydning. |

Parser og serverlogik testes primært uden netværk. Hvor selve forbindelsens livsforløb skal kontrolleres automatisk, bruges en lokal testserver med en ledig port, tidsgrænser og sikker lukning efter testen. Samtidighedstests koordinerer tråde med signaler frem for at håbe, at en bestemt pause rammer rigtigt.

Manuel integrationstest dokumenteres med trin, forventet resultat, faktisk resultat og eventuelle rettelser:

1. Start server og tre klienter som `alice`, `bob` og `charlie`. Alle sender og modtager i `lobby`.
2. Forsøg login med et optaget navn. Prøv derefter et ledigt navn.
3. Flyt `charlie` til `java` og afvent bekræftelsen. Send nye beskeder i begge rum og kontroller isoleringen.
4. Send privat mellem forskellige rum. Kontroller også den tredje klients vindue for fravær af privatbeskeden.
5. Send fejlformateret input gennem en testforbindelse. Send derefter en gyldig kommando, og kontroller at serveren og forbindelsen stadig fungerer.
6. Afslut én klient med `/quit` og en anden ved at stoppe processen. Genbrug begge brugernavne og kontroller medlemskaberne.
7. Gentag de centrale scenarier med mindst én JavaFX-klient og én konsolklient tilsluttet samtidig.
8. Test JavaFX med optaget navn, utilgængelig server, offline privat modtager, serverstop og vindueslukning. Vindue og knapper skal reagere korrekt under ventetid og fejl.
9. Test kapacitetsgrænsen: ekstra forbindelse afvises, og en ny forbindelse accepteres efter frigivelse af en plads.

Der registreres kun beståede tests, når de faktisk er kørt. JavaFX testes manuelt; den fælles netværks- og serverlogik dækkes automatisk.

## 9. Forslag til kalender for én person

Datoerne er arbejdsmål, som kan justeres efter den tid, der er til rådighed. Færdigkriterierne bestemmer rækkefølgen.

| Dato | Mål |
| --- | --- |
| 10. september | Plan og trin 1: projektopsætning og første forbindelse. |
| 11. september | Trin 2: protokol og parsertests. |
| 12. september | Trin 3: flere klienter, login og broadcast. |
| 13. september | Trin 4: rum og private beskeder. |
| 14. september | Trin 5: oprydning, fejl og minimumstest. |
| 15.–16. september | Trin 6: JavaFX og integration mellem klienttyperne. |
| 17. september | Rettelser, README, diagrammer og generalprøve. |
| 18. september | Demonstration og præsentation. |

Ved tidspres forenkles JavaFX-layout og styling. De obligatoriske funktioner, JavaFX-udvidelsen, tests og dokumentation er fortsat færdigkriterier.

## 10. README og præsentation

README skal afspejle den færdige kode og indeholde:

- Projektets formål, Java-/JavaFX-versioner og den valgte udvidelse.
- Præcis opstart af server, konsolklient og JavaFX-klient i IntelliJ og gennem dokumenterede Maven-kommandoer samt vært/port og flere samtidige klienter.
- Konsolkommandoer, alle protokoltyper og regler for navne, rum, tidspunkter og fejl.
- Klassediagram, som matcher de faktiske klasser.
- Forklaring af trådpulje, klienternes tråde, delte ressourcer og låse.
- Ét sekvensdiagram over et fejlforløb, foreslået: optaget brugernavn efterfulgt af et vellykket nyt forsøg. Diagrammer kan indsættes som Mermaid.
- Automatiske og manuelle testresultater og kendte begrænsninger.
- Beskrivelse og test af JavaFX-udvidelsen og dens genbrug af netværkskoden.
- 3–5 væsentlige AI-situationer med opgave, værktøj, forslag, egen vurdering/ændring og faktisk kontrol/test.

En oplagt mulig AI-situation er vurderingen af, om en trådsikker map alene gør et rumskifte sikkert. Den dokumenteres først som et gennemført eksempel, når den valgte løsning er implementeret, gennemgået og testet.

Forslag til præsentation på højst 15 minutter: 2 minutter om opbygning, 6 minutter til demonstration med tre samtidige klienter og JavaFX, 4 minutter om protokol/tråde/oprydning og 3 minutter om tests samt et kontrolleret, ændret eller afvist AI-forslag.

Næste personlige arbejdstrin er at åbne projektet i IntelliJ, læse klasserne trinvis og gennemføre demonstrationsforløbet. AI-dokumentationen skal suppleres med din egen vurdering efter kodegennemgangen.
