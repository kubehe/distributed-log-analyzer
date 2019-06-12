# Projekt na zajęcia PRORR - Jakub Bączek, Szymon Petruczynik

## Temat

Rozproszony system do analizy logów

## Założenia

System ma na celu analizę logów z serwera, obecnie możliwe jest tylko analizowanie plików z apache, planujemy wsparcie dla servletów java - Netty 

## Architektura 

Zastosowaliśmy architekturę PCAM:
- Process - przetworzenie danych na chunki i ładowanie do kolejki asynchronicznie
- Communicate - skomunikowanie wszystkich procesów poprzeż kolejki rabbitMQ
- Aggregate - agregowanie danych i wysłanie do końcowej kolejki z której master z powrotem pobiera przetworzone dane
- Map - łączenie kolejnych wyników obliczeń pobranych z końcowej kolejki przez mastera i wysyłanie ich przez sse do użytkownika

System jest podzielony na moduły:
- master - główny serwis zajmujący się dzieleniem danych na chunki, a następnie wrzucenie ich do kolejki `distributed.log.analyzer.processing`
- slave - serwis który można skalować - zajmuje się najpierw sparsowaniem danych i ich ustrukturyzowaniem - wynik zostaje wrzucony do kolejki `distributed.log.analyzer.calculation`, następnie następuje agregacja danych i wrzucenie do kolejki `distributed.log.analyzer.aggregation` z której master pobiera częściowe wyniki i scala
- dashboard - serwis frontendowy - wyświetla wykres z metodami http - możliwość rozszerzenia i zbudowania dashboarda

## Technologie

Oparliśmy nasz system na Spring Webflux, RabbitMQ, oraz Dockerze.
Dodatkowo przykładowa analiza metod http została zobrazowana na wykresie zrobionym w Reactcie.
Wykorzystaliśmy Dockera do skalowania systemu - skalowanie kontenerów slave

## Instrukcja obsługi

Aby odpalić środowisko należy mieć skonfigurowanego Dockera, przykładowy skrypt dla Ubuntu:
``` bash

#!/usr/bin/env bash

snap install -y docker

groupadd docker # te 3 linie pozwalaja na wykonanie dockera bez sudo - wymaga zrestartowania sesji użytkownika
usermod -aG docker $USER
newgrp docker

apt install -y docker-compose

```

Jeżeli użytkownik chce odpalić system na więcej niż jednym serwerze potrzebny jest Docker Machine.

System można zbudować komendą:
```bash
docker-compose run --scale slave=4 # komenda scale spowoduje odpalenie 4 instancji slave
```

Przetestowanie wydajności systemu polega na wykonaniu serii działań:

- otworzenie przeglądarki pod adresem http://localhost/result - endpoint sse z agregowanymi danymi, lub http://localhost:3000 - widok z wykresem w reactcie
- wysłanie na backend pliku z logami apache, przykładowo: `curl -F 'file=@/home/kubehe/Projects/distributed-log-analyzer/master/src/resources/test-logs/normal.log' http://localhost:80/upload`

- następnie rozpocznie się analizowanie danych - aby podejrzeć działanie można wejść na panel zarządzania rabbitMQ, http://localhost:15672 login: `log`, hasło: `log`

## Wnioski
