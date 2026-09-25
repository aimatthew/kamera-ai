# Kamera AI

Otwarta aplikacja Android, która analizuje obraz z tylnego aparatu w czasie rzeczywistym. Model YOLO11s działa lokalnie na telefonie — obraz nie jest wysyłany do internetu ani zapisywany.

## Funkcje

- podgląd CameraX na żywo,
- wykrywanie 80 klas COCO przez YOLO11s,
- ramki, polskie nazwy i poziom pewności,
- podsumowanie obiektów aktualnie widocznych w kadrze,
- odrzucanie nakładających się wyników metodą NMS,
- przetwarzanie offline przez ONNX Runtime,
- brak kont, serwera i płatnego API.

## Wymagania

- Android 8.0 (API 26) lub nowszy,
- aparat,
- Android Studio z JDK 17.

## Uruchomienie

1. Otwórz katalog projektu w Android Studio.
2. Poczekaj na synchronizację Gradle.
3. Podłącz telefon lub uruchom emulator z aparatem.
4. Uruchom konfigurację `app` i zaakceptuj dostęp do aparatu.

Model `app/src/main/assets/yolo11s.onnx` jest dołączany bezpośrednio do APK. Można odtworzyć go poleceniem:

```powershell
python -m pip install ultralytics onnx
python tools/export_model.py
```

## Budowanie APK

```powershell
./gradlew.bat :app:assembleDebug
```

Wynik znajduje się w `app/build/outputs/apk/debug/app-debug.apk`.

## Aktualizacje przez GitHub

Aplikacja ma menu aktualizacji dostępne pod przyciskiem w prawym górnym rogu.
Przed opublikowaniem ustaw repozytorium w zasobie `github_repository` w pliku
`app/src/main/res/values/strings.xml`, używając formatu `właściciel/repozytorium`.

Aktualizator odczytuje najnowsze publiczne wydanie z GitHub Releases i pobiera
pierwszy dołączony plik z rozszerzeniem `.apk`. Tag wydania powinien odpowiadać
wartości `versionName`, na przykład `v1.1.0`. Każda aktualizacja musi być podpisana
tym samym kluczem co poprzednio zainstalowana wersja aplikacji.

## Architektura

```text
CameraX -> RGBA Bitmap -> letterbox 640x640 -> YOLO11s ONNX
        -> próg pewności -> NMS -> ramki na podglądzie
```

Domyślny próg pewności to `0.45`, a próg IoU dla NMS to `0.50`. Można je zmienić w konstruktorze `OnnxYoloDetector`.

## Prywatność

Klatki są analizowane wyłącznie w pamięci telefonu i od razu zwalniane. Aplikacja nie ma uprawnienia do internetu i nie zapisuje zdjęć ani filmów.

## Licencja

Projekt oraz dołączone wagi YOLO są udostępniane na zasadach AGPL-3.0. ONNX Runtime korzysta z licencji MIT, a biblioteki AndroidX z właściwych im licencji otwartego oprogramowania.
