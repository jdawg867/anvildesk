import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "m8_java_jni", Path(__file__).with_name("verify-m8-java-jni-declarations.py")
)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

class JavaNativeDeclarationTests(unittest.TestCase):
    def test_public_private_and_protected_native_methods(self):
        found = mod.declarations("""
            public native void start();
            private static native boolean connected();
            protected native long pointer(int value);
        """)
        self.assertEqual(
            {
                ("start", "()V"): False,
                ("connected", "()Z"): True,
                ("pointer", "(I)J"): False,
            },
            found,
        )

    def test_duplicate_native_declarations_rejected(self):
        with self.assertRaises(ValueError):
            mod.declarations("public native void start(); private native void start();")

    def test_non_native_methods_ignored(self):
        self.assertEqual({}, mod.declarations("public void start() {}"))

if __name__ == "__main__":
    unittest.main()
