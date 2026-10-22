"""Offline, dependency-free publication checks. Never prints matched secret values.

By default inspect tracked and non-ignored untracked files in the working tree.
--history inspects every reachable Git blob, including deleted files, without
rewriting history. A failure requires review/rotation, not automatic deletion.
This heuristic check complements a dedicated secret scanner; it is not a proof
that credentials or third-party copyrighted material are absent.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
TOKEN = re.compile(r"\bsk-[A-Za-z0-9_-]{20,}\b")
JWT = re.compile(r"\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{20,}\b")
PRIVATE = re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----")
ASSIGNMENT = re.compile(
    r"(?i)^\s*(?:(?:public|private|protected|static|final|volatile|export)\s+)*(?:(?:String|var)\s+)?"
    r"(?:[\w.-]*(?:password|passwd|secret[_-]?key|token[_-]?secret[_-]?key|"
    r"api[_-]?key|access[_-]?key|smtp[_-]?password|jwt[_-]?secret[_-]?base64))"
    r"\s*[:=](?!=)\s*([\"']?)([^\"'\r\n;,}\s]+)"
)
ALLOWED = {"ci-only-test-password", "simulation-only", "test-only", "unused", "invalid",
           "password", "true", "false", "null", "none", "str", "string", "required"}
MAX_BYTES = 2 * 1024 * 1024


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, check=True, capture_output=True).stdout


def findings(path, raw, oid=None):
    if len(raw) > MAX_BYTES or b"\x00" in raw:
        return []
    try:
        content = raw.decode("utf-8-sig")
    except UnicodeError:
        return []
    result = []
    for number, line in enumerate(content.splitlines(), 1):
        rules = set()
        for name, pattern in [("model-key", TOKEN), ("signed-jwt", JWT), ("private-key", PRIVATE)]:
            if pattern.search(line):
                rules.add(name)
        if not line.lstrip().startswith(("#", "//", "*")):
            for match in ASSIGNMENT.finditer(line):
                if path.endswith((".java", ".py")) and not match[1]:
                    continue  # Variable/method expressions are not credential literals.
                value = match[2].lower()
                if (len(value) >= 4 and value not in ALLOWED and
                        not value.startswith(("$", "<", "os.", "system.", "env.", "getenv", "process.",
                                              "config.", "settings.", "arguments.", "request.", "this.", "self.", "re.", "(?:")) and
                        not re.fullmatch(r"[A-Z_][A-Z_0-9.]*", match[2])):
                    rules.add("credential-literal")
        for rule in sorted(rules):
            item = {"path": path, "line": number, "rule": rule}
            if oid:
                item["blob"] = oid
            result.append(item)
    return result


def check_worktree():
    paths = set(git("ls-files", "-z").decode().split("\0"))
    paths.update(git("ls-files", "--others", "--exclude-standard", "-z").decode().split("\0"))
    result, count = [], 0
    for path in sorted(paths - {""}):
        file = ROOT / path
        if file.is_file() and not file.is_symlink():
            count += 1
            result.extend(findings(path, file.read_bytes()))
    return count, result


def check_history():
    objects = git("rev-list", "--objects", "--all").decode().splitlines()
    locations = dict(line.split(" ", 1) for line in objects if " " in line)
    request = "".join(oid + "\n" for oid in locations).encode()
    headers = subprocess.run(["git", "cat-file", "--batch-check"], cwd=ROOT,
                             input=request, capture_output=True, check=True).stdout.decode().splitlines()
    blobs = [line.split()[0] for line in headers
             if len(line.split()) == 3 and line.split()[1] == "blob" and int(line.split()[2]) <= MAX_BYTES]
    raw = subprocess.run(["git", "cat-file", "--batch"], cwd=ROOT,
                         input="".join(oid + "\n" for oid in blobs).encode(),
                         capture_output=True, check=True).stdout
    offset, result = 0, []
    for oid in blobs:
        end = raw.index(b"\n", offset)
        size = int(raw[offset:end].split()[-1])
        content = raw[end + 1:end + 1 + size]
        offset = end + 1 + size + 1
        result.extend(findings(locations[oid], content, oid))
    return len(blobs), result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--history", action="store_true")
    parser.add_argument("--report", type=Path, help="Write only paths, line numbers, rules and blob IDs")
    args = parser.parse_args()
    count, items = check_history() if args.history else check_worktree()
    report = {"scope": "reachable-git-history" if args.history else "working-tree",
              "status": "REVIEW_REQUIRED" if items else "PASS", "filesOrBlobs": count,
              "findings": items, "matchedValuesIncluded": False}
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key != "findings"}))
    for item in items:
        print(json.dumps(item, ensure_ascii=True))
    return 1 if items else 0


if __name__ == "__main__":
    sys.exit(main())
