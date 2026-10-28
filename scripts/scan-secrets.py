#!/usr/bin/env python3
"""Looks for committed credentials in tracked text files: Cesium ion / OAuth access tokens
(JWTs), private keys, cloud keys, and token=value pairs with a long value. Exit 1 on a hit.
Matches are listed by file and line only, never printed in full."""
import re, subprocess, sys

PATTERNS = {
    "JWT (ion or OAuth access token)": re.compile(r"eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{10,}"),
    "private key": re.compile(r"-----BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
    "AWS access key": re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    "GitHub token": re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}\b"),
    "token/secret assigned a long literal": re.compile(
        r"(?i)(access_?token|refresh_?token|api_?key|secret|password)\s*[=:]\s*[\"'][A-Za-z0-9._~+/=-]{24,}[\"']"),
}
# Contract test cases hold deliberately fake tokens (their payload says "fake-endpoint-token",
# the signature is the word "signature") to prove the client redacts them.
FIXTURES = "spec/ion-contract/cases/"
# Other obviously fake values.
ALLOW = re.compile(r"(?i)(example|fake|dummy|placeholder|REDACTED|SECRET\b|xxxx|test-token|not-a-real)")

files = subprocess.run(["git", "ls-files"], capture_output=True, text=True, check=True).stdout.split("\n")
hits = []
for path in filter(None, files):
    if path.startswith(FIXTURES):
        continue
    try:
        text = open(path, encoding="utf-8").read()
    except (UnicodeDecodeError, OSError):
        continue  # binary
    for n, line in enumerate(text.split("\n"), 1):
        for name, pattern in PATTERNS.items():
            if pattern.search(line) and not ALLOW.search(line):
                hits.append(f"{path}:{n}: {name}")
print(f"Secrets scan: {len(files)} tracked files, {len(hits)} possible credential(s).")
for h in hits:
    print("  " + h)
sys.exit(1 if hits else 0)
