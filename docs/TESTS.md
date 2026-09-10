# Test og demonstration

Resultaterne opdateres efter de faktiske kørsler. Projektet testes med Java 21.

## Automatiske kontroller

| Kontrol | Status |
| --- | --- |
| Første TCP-forbindelse | Bestået: server og konsolklient udvekslede `Hej fra IntelliJ — æøå` i separate Java-processer. |
| MessageParser | 99 beståede testtilfælde: format, typer, retning, tomme felter, payload, linjeinjektion og kalenderdatoer. |
| TCP med tre klienter | Bestået med tre separate sockets: broadcast, rumisolering, private beskeder, dubletlogin og nyt forsøg, fejlformater efterfulgt af gyldigt input, QUIT/EOF og genbrug af navne. |
| Tre faktiske konsolprocesser | Bestået: samtidig chat, rumskifte, private beskeder, /quit og serverstop uden ekstra Enter. |
| Serverlogik og samtidighed | 12 beståede tests i ChatServiceTest og ChatStateTest, herunder samtidig navnereservation og gentagen oprydning. |
| Socketintegration og nedlukning | 8 beståede tests i ChatServerTest og ClientHandlerTest: kapacitet før login, EOF/RST, serverstop, samtidige hele linjer og close under blokeret skrivning. |
| Fælles klientkode og konsol | 12 beståede tests: 9 sockettests og 3 tests med konsol som separat Java-proces. Omfatter tidlig broadcast, QUIT-fallback og hurtig fejlet PRIVATE efterfulgt af JOIN. |
| Ren Maven-build | `clean verify` bestået: 131 tests, 0 fejl, 0 oversprungne; JAR bygget med Java 21. |

## JavaFX afprøvet 10. september 2026

Den faktiske JavaFX-brugerflade er betjent og visuelt kontrolleret på macOS med én GUI-klient (`alice`) og to konsolprocesser (`bob`, `charlie`) tilsluttet samme lokale server. En lokal jpackage-app under `target` blev brugt til UI-kontrollen; koden er den samme som ved Maven-opstart.

Følgende er afprøvet med bestået resultat:

- Login og rumbeskeder begge veje mellem GUI og konsol, inklusive `æøå` og `|` i teksten.
- Charlie skifter til `java`; Bobs nye besked i `lobby` når Alice og Bob, men ikke Charlie.
- Private beskeder begge veje mellem Alice og Charlie på tværs af rum. Bob modtager ikke privatteksten.
- Rumvalg i GUI ændrer det bekræftede rum og beskedfeltets label.
- Offline privat modtager giver en synlig fejl; den skrevne tekst bevares.
- Normal afbrydelse giver offlinevisning og deaktiverede sendefelter.
- Genforbindelse, afvist optaget navn og nyt loginforsøg med et ledigt navn på samme forbindelse.
- Serverstop afslutter begge konsolprocesser uden ekstra Enter og viser offline/genforbindelse i GUI.
- Forbindelse til en stoppet server viser fejl uden at fryse vinduet.
- Layout ved 820×660 og vinduets minimum på 640×540: felter og knapper kan bruges uden overlap.
- Lukning af GUI-vinduet afslutter appprocessen.

Code review rettede desuden håndtering af sene callbacks efter afbrydelse: disconnect invaliderer forbindelsens generation. Login, afsendelse, normal afbrydelse, genforbindelse og vindueslukning er afprøvet igen efter denne rettelse. Fokus står nu i skrivefeltet efter login. Det præcise callback-kapløb er kontrolleret gennem kodegennemgang, ikke en deterministisk GUI-test. Den afsluttende `verify` efter rettelserne bestod også alle 131 tests.

## Manuel generalprøve

Disse trin er din egen generalprøve til en demonstration på højst 15 minutter. De gennemførte udviklingskontroller står ovenfor. Markér først din egen generalprøve bestået, når du selv har udført og kan forklare forløbet.

| Trin | Handling | Forventet resultat | Resultat |
| --- | --- | --- | --- |
| 1 | Start server og tre klienter: alice, bob, charlie. | Alle kan sende og modtage samtidig i lobby. | Afventer. |
| 2 | Forsøg login som bob fra en ekstra klient, derefter som dana. | bob afvises; dana accepteres på samme forbindelse. | Afventer. |
| 3 | Flyt charlie til java og send i begge rum. | Kun medlemmer af det pågældende rum modtager nye beskeder. | Afventer. |
| 4 | Send privat fra bob til charlie. | Kun charlie får privatteksten; bob får særskilt status. | Afventer. |
| 5 | Send privat til en offlinebruger og skift til et ukendt rum. | Tydelig fejl; forbindelsen og gammelt medlemskab bevares. | Afventer. |
| 6 | Send en fejlformateret linje og derefter en gyldig. | Fejl efterfulgt af normalt svar på samme forbindelse. | Afventer. |
| 7 | Afslut bob med quit og stop charlies proces uventet. | Begge navne kan bruges igen; gamle medlemskaber fjernes. | Afventer. |
| 8 | Brug både JavaFX og konsol samtidig. | Samme rum, privatbeskeder og fejl virker på tværs af klienttyper. | Afventer. |
| 9 | Stop serveren mens klienterne venter på input. | Klienterne melder afbrudt forbindelse og frigiver ressourcer. | Afventer. |
| 10 | Luk JavaFX-vinduet og prøv forbindelse til en utilgængelig server. | Proces afslutter, og nyt forbindelsesforsøg viser en forståelig fejl uden frosset GUI. | Afventer. |

Automatiske sockettests supplerer disse trin med kapacitetsgrænsen, login-kapløb og gentagen oprydning. En manuel generalprøve er stadig nødvendig før fremlæggelsen, så du også kan forklare det, der vises.
