# H@CKPL decoder/unpacker

To małe narzędzie służy do rozpakowywania i wyciągania artykułów z pierwszych
czterech części starego cyberzin'a H@CKPL.

## Budowanie

Program napisany jest w Kotlinie, do działania wymaga Javy 13.
```
$ ./gradlew shadowJar

$ java -jar build/libs/hackpldecode-1.0-all.jar
Missing required options and parameters: '--issue=<issue>', 'FILE'
Usage: extract [-e=TXT] -i=<issue> [-u=EXE] FILE
Extractor/unpacker for H@CKPL zines 001-004
    FILE              Path to the H@CKPL.EXE file (required)
-e, --extract=TXT     Extract texts and store them in the generated TXT file
                        (encoded as UTF-8)
-i, --issue=<issue>   Issue number: 1, 2, 3 or 4 (required)
-u, --unpack=EXE      Unpack and save the generated EXE file
FAIL: 2
```

## Przykładowe użycie

Toola można użyć np. w ten sposób:

```
$ java -jar build/libs/hackpldecode-1.0-all.jar -i 1 -u unpacked1.exe -e unpacked1.txt H@CKPL.EXE
H@CKPL unpacker/decoder, written by @antekone
https://anadoxin.org/blog

- Unpacked: 238325 bytes -> 245024 bytes
Written to: unpacked1.exe
- Extracted 73372 bytes
Written to: unpacked1.txt

Enjoy!
```

Rozpakowany plik EXE zostanie zapisany pod nazwą podaną w argumencie `-u`, z kolei
wyciągnięte artykuły (w UTF-8) znajdą się w pliku o nazwie z argumentu `-e`. 

## FAQ

Q: Zamiast poprawnego tekstu w plikach wyjściowych mam same krzaki. Program nie działa!
A: Musisz podać odpowiedni numer przy argumencie `-i`. Program ma stablicowane hasła,
   bruteforcowanie na JVM trwałoby zbyt długo.
