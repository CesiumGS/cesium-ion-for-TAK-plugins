#!/usr/bin/env python3
"""Lists what the plugins are built from, for a security reviewer: Java dependencies (from the
Gradle files), the Unreal plugin's modules and plugin dependencies, and the Cesium for Unreal
version the docs name. Writes Markdown to stdout."""
import glob, re

print("# Third-party inventory\n")
print("## Java (shared core, ATAK plugin)\n")
deps = {}
for path in sorted(glob.glob("**/build.gradle", recursive=True)):
    for line in open(path, encoding="utf-8"):
        m = re.search(r"(?:implementation|api|compileOnly|runtimeOnly|testImplementation)\s*[\(\s]['\"]([^'\"]+:[^'\"]+:[^'\"]+)['\"]", line)
        if m:
            deps.setdefault(m.group(1), set()).add(path)
for coord, paths in sorted(deps.items()):
    print(f"- `{coord}` ({', '.join(sorted(paths))})")
if not deps:
    print("- none declared in build.gradle files (the shared core has no runtime dependencies)")
print("\n## Unreal (VR-TAK plugin)\n")
for path in sorted(glob.glob("vrtak/**/*.uplugin", recursive=True)):
    text = open(path, encoding="utf-8").read()
    print(f"- `{path}`: plugins {re.findall(r'\"Name\"\\s*:\\s*\"([^\"]+)\"', text)}")
for path in sorted(glob.glob("vrtak/**/*.Build.cs", recursive=True)):
    text = open(path, encoding="utf-8").read()
    mods = sorted(set(re.findall(r'"([A-Za-z0-9_]+)"', " ".join(re.findall(r"DependencyModuleNames\.AddRange\(.*?\}\)", text, re.S)))))
    print(f"- `{path}` modules: {', '.join(mods)}")
print("\nCesium for Unreal 2.17 (UE 5.3 build) ships alongside the plugin; see vrtak/README.md.")
