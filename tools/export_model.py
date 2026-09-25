from pathlib import Path
import shutil

from ultralytics import YOLO


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app" / "src" / "main" / "assets"
DESTINATION = ASSETS / "yolo11s.onnx"


def main() -> None:
    ASSETS.mkdir(parents=True, exist_ok=True)
    model = YOLO("yolo11s.pt")
    exported = Path(
        model.export(
            format="onnx",
            imgsz=640,
            dynamic=False,
            simplify=True,
            nms=False,
            opset=17,
        )
    )
    shutil.copy2(exported, DESTINATION)
    print(f"Model zapisany: {DESTINATION}")


if __name__ == "__main__":
    main()
