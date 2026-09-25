# Model

Aplikacja oczekuje pliku:

`yolo11s.onnx`

Wygeneruj go z katalogu g˘wnego projektu:

```powershell
python tools/export_model.py
```

Skrypt pobiera oficjalne wagi YOLO11s i eksportuje je do ONNX bez wbudowanego NMS.
Model oraz kod Ultralytics sĄ obj©te licencjĄ AGPL-3.0.
