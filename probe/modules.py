#!/usr/bin/env python3
"""Prints the source directories of the closure of a Kotlin repo module's project dependencies.

Each module's directories come after its name-based destructuring setting: modules built for Gradle
compatibility, by an older compiler, are built without the newer language features.

Usage: modules.py [--modules | --why] ROOT_PROJECT... (run from ~/src/kotlin), e.g. modules.py :compiler:cli-jvm
Test configurations are skipped; so are the stdlib, which the probe adds itself, and EXCLUDED.
"""
import os
import re
import sys

EXCLUDED = {":kotlin-stdlib", ":kotlin-stdlib-common", ":compiler:javac-wrapper"}


def project_dirs():
    text = open("settings.gradle.kts").read()
    text = re.sub(r"=\s*\n\s*", "= ", text)
    dirs = {}
    for m in re.finditer(r'project\("([^"]+)"\)\.projectDir\s*=\s*(?:File\()?"\$rootDir/([^"]+)"', text):
        dirs[m.group(1)] = m.group(2)
    return dirs


def project_dir(project, dirs):
    return dirs.get(project, project.strip(":").replace(":", "/"))


def dependencies(directory):
    path = os.path.join(directory, "build.gradle.kts")
    if not os.path.exists(path):
        return []
    result = []
    for m in re.finditer(r'(\w+)\s*\(\s*(?:(\w+)\s*\(\s*)?project\(\s*"([^"]+)"', open(path).read()):
        configuration = m.group(1)
        if configuration.lower().startswith("test") or "Test" in configuration or m.group(2) == "platform":
            continue
        if m.group(3).startswith(":"):
            result.append(m.group(3))
    return result


def gradle_compatible(directory):
    path = os.path.join(directory, "build.gradle.kts")
    return os.path.exists(path) and "configureKotlinCompileTasksGradleCompatibility()" in open(path).read()


def source_dirs(directory):
    result = []
    main = os.path.join(directory, "src", "main")
    if os.path.isdir(main):
        result += [os.path.join(main, d) for d in ("kotlin", "java") if os.path.isdir(os.path.join(main, d))]
    elif os.path.isdir(os.path.join(directory, "src")):
        result.append(os.path.join(directory, "src"))
    for generated in ("gen", "generated"):
        if os.path.isdir(os.path.join(directory, generated)):
            result.append(os.path.join(directory, generated))
    return result


def main(args):
    list_modules = "--modules" in args
    why = "--why" in args
    roots = [a for a in args if not a.startswith("--")]
    dirs = project_dirs()
    seen = []
    parents = {}
    stack = [(r, None) for r in reversed(roots)]
    while stack:
        project, parent = stack.pop()
        if project in seen or project in EXCLUDED:
            continue
        seen.append(project)
        parents[project] = parent
        stack.extend((d, project) for d in reversed(dependencies(project_dir(project, dirs))))
    for project in seen:
        if why:
            print(project, "<-", parents[project])
        elif list_modules:
            print(project)
        else:
            directory = project_dir(project, dirs)
            mode = "only-syntax" if gradle_compatible(directory) else "complete"
            print("-Xname-based-destructuring=" + mode)
            for d in source_dirs(directory):
                print(d)


if __name__ == "__main__":
    main(sys.argv[1:])
