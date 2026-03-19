import argparse
import subprocess
import sys
from pathlib import Path


def run_step(command: list[str], cwd: Path, step_name: str) -> None:
    print(f"\n[STEP] {step_name}")
    print(f"[CMD ] {' '.join(command)}")
    result = subprocess.run(command, cwd=str(cwd), check=False)
    if result.returncode != 0:
        raise RuntimeError(f"{step_name} failed with exit code {result.returncode}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Sync product vectors and knowledge vectors in one command")
    parser.add_argument("--kb-table", default="knowledge_embeddings", help="PostgreSQL table name for knowledge vectors")
    parser.add_argument(
        "--replace-knowledge-source",
        action="store_true",
        help="Replace existing advisor knowledge source rows before vectorization",
    )
    parser.add_argument(
        "--skip-knowledge",
        action="store_true",
        help="Only sync product vectors and skip knowledge vectorization",
    )
    parser.add_argument(
        "--hash-only",
        action="store_true",
        help="Use local hash embeddings for knowledge vectorization only",
    )
    args = parser.parse_args()

    script_dir = Path(__file__).resolve().parent

    run_step(
        [
            sys.executable,
            "main.py",
            "--sync-all-vectors",
        ],
        cwd=script_dir,
        step_name="Sync all product vectors",
    )

    if not args.skip_knowledge:
        command = [
            sys.executable,
            "knowledge-vectorizer.py",
            "--table",
            args.kb_table,
        ]
        if args.hash_only:
            command.append("--hash-only")
        if args.replace_knowledge_source:
            command.append("--replace-source")

        run_step(
            command,
            cwd=script_dir,
            step_name="Sync knowledge vectors",
        )

    print("\n[OK] Vector synchronization completed.")


if __name__ == "__main__":
    main()
