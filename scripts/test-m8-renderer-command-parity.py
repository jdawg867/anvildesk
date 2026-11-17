#!/usr/bin/env python3
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/verify-m8-renderer-command-parity.py"
spec = spec_from_file_location("m8_command_parity", SCRIPT)
mod = module_from_spec(spec)
spec.loader.exec_module(mod)
JAVA = (ROOT / "app/src/main/java/io/github/jdawg867/anvildesk/m8"
        / "M8RendererCommandContract.java").read_text()
CPP = (ROOT / "scripts/m8-renderer-command-boundary.h").read_text()


class ParityTests(unittest.TestCase):
    def test_current_contract(self):
        mod.verify(JAVA, CPP)

    def test_java_opcode_drift(self):
        with self.assertRaises(ValueError):
            mod.verify(JAVA.replace("COMMAND_VIEWPORT = 1", "COMMAND_VIEWPORT = 4"), CPP)

    def test_native_result_drift(self):
        with self.assertRaises(ValueError):
            mod.verify(JAVA, CPP.replace("BackendRejected = 3", "BackendRejected = 9"))

    def test_native_lease_removed(self):
        with self.assertRaises(ValueError):
            mod.verify(JAVA, CPP.replace("registry_.acquire(handle)", "registry_.lookup(handle)"))

    def test_java_native_method_injected(self):
        with self.assertRaises(ValueError):
            mod.verify(JAVA.replace("private M8RendererCommandContract() {}",
                                    "private M8RendererCommandContract() {}\n    native int dispatch();"), CPP)


if __name__ == "__main__":
    unittest.main(verbosity=2)
