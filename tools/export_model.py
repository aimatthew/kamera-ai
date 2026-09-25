from pathlib import Path
import shutil

from ultralytics import YOLO


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app" / "src" / "main" / "assets"
DESTINATION = ASSETS / "yolo26s.onnx"


def main() -> None:
    ASSETS.mkdir(parents=True, exist_ok=True)
    model = YOLO("yolo26s.pt")
    exported = Path(
        model.export(
            format="onnx",
            imgsz=320,
            dynamic=False,
            simplify=True,
            # Surowe wyjście one-to-many jest najbardziej zgodne z NNAPI
            # i zachowuje format obsługiwany przez dekoder aplikacji.
            nms=None,
            opset=17,
        )
    )
    shutil.copy2(exported, DESTINATION)
    print(f"Model zapisany: {DESTINATION}")


if __name__ == "__main__":
    main()
