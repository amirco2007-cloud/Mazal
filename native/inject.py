#!/usr/bin/env python3
"""Inject Mazal native sources into the freshly generated Capacitor Android project
and patch AndroidManifest.xml. Run from the repo root after `cap add android`,
before `cap sync` / gradle. Idempotent and fails loudly if anchors are missing.
"""

import os
import shutil
import sys

ANDROID_MAIN = os.path.join("android", "app", "src", "main")
JAVA_SRC = os.path.join("native", "java")
RES_SRC = os.path.join("native", "res")
MANIFEST = os.path.join(ANDROID_MAIN, "AndroidManifest.xml")

QUERIES_MARKER = "<!-- mazal-launcher-query -->"

QUERIES_INTENT = """        """ + QUERIES_MARKER + """
        <intent>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent>
"""

QUERIES_BLOCK = """    <queries>
""" + QUERIES_INTENT + """    </queries>
"""

SERVICE_BLOCK = """        <service
            android:name=".AppWatchAccessibilityService"
            android:exported="false"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>
"""


def fail(msg):
    print("inject.py ERROR: " + msg, file=sys.stderr)
    sys.exit(1)


def copy_tree(src, dst):
    for root, _dirs, files in os.walk(src):
        rel = os.path.relpath(root, src)
        target_dir = os.path.join(dst, rel) if rel != "." else dst
        os.makedirs(target_dir, exist_ok=True)
        for f in files:
            shutil.copy2(os.path.join(root, f), os.path.join(target_dir, f))
            print("  copied " + os.path.join(target_dir, f))


def copy_sources():
    if not os.path.isdir(ANDROID_MAIN):
        fail("generated project not found at " + ANDROID_MAIN + " (run `cap add android` first)")
    print("Copying Java sources...")
    copy_tree(JAVA_SRC, os.path.join(ANDROID_MAIN, "java"))
    print("Copying resources...")
    copy_tree(RES_SRC, os.path.join(ANDROID_MAIN, "res"))


def patch_manifest():
    if not os.path.isfile(MANIFEST):
        fail("manifest not found at " + MANIFEST)
    with open(MANIFEST, "r", encoding="utf-8") as fh:
        xml = fh.read()

    # 1) Service — insert before </application>
    if "AppWatchAccessibilityService" in xml:
        print("Service already present; skipping.")
    else:
        if "</application>" not in xml:
            fail("no </application> anchor in manifest")
        xml = xml.replace("</application>", SERVICE_BLOCK + "    </application>", 1)
        print("Inserted accessibility service.")

    # 2) Queries — merge into existing <queries> or add a new block.
    # Use our own marker for idempotency (the activity already contains a
    # LAUNCHER category, so we can't key off that string).
    if QUERIES_MARKER in xml:
        print("Launcher query already present; skipping.")
    elif "<queries>" in xml:
        xml = xml.replace("<queries>", "<queries>\n" + QUERIES_INTENT, 1)
        print("Merged launcher intent into existing <queries>.")
    else:
        if "</manifest>" not in xml:
            fail("no </manifest> anchor in manifest")
        xml = xml.replace("</manifest>", QUERIES_BLOCK + "</manifest>", 1)
        print("Inserted <queries> block.")

    with open(MANIFEST, "w", encoding="utf-8") as fh:
        fh.write(xml)
    print("Manifest patched: " + MANIFEST)


def main():
    copy_sources()
    patch_manifest()
    print("inject.py done.")


if __name__ == "__main__":
    main()
