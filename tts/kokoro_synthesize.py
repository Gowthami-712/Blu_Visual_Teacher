import argparse
import sys
from pathlib import Path

import soundfile as sf
from kokoro_onnx import Kokoro


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True)
    parser.add_argument("--voices", required=True)
    parser.add_argument("--voice", default="af_heart")
    parser.add_argument("--speed", type=float, default=1.0)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    text = sys.stdin.read().strip()
    if not text:
        raise ValueError("No narration text received on standard input.")

    kokoro = Kokoro(args.model, args.voices)
    samples, sample_rate = kokoro.create(
        text,
        voice=args.voice,
        speed=args.speed,
        lang="en-us"
    )

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    sf.write(str(output), samples, sample_rate)
    print(f"Kokoro narration saved: {output}")


if __name__ == "__main__":
    main()
