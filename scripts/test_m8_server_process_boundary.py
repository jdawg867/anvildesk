import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "m8_boundary", Path(__file__).with_name("verify-m8-server-process-boundary.py")
)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

SAMPLE = """
static int argc = 0;
static char** argv = nullptr;
static JNIEnv* serverEnv = nullptr;
static jobject thiz = nullptr;
pthread_create(&t, nullptr, worker, vm);
const int serverStatus = dix_main(argc, (char**) argv, (char*[]) { nullptr });
"""

class BoundaryTests(unittest.TestCase):
    def test_in_process_thread_stays_blocked(self):
        result = mod.verify(SAMPLE)
        self.assertIn("M8_NATIVE_LOAD_AUTHORIZED=false", result)
        self.assertIn("STOP_AND_JOIN_VERIFIED=false", result)

    def test_unpatched_process_exit_rejected(self):
        with self.assertRaises(ValueError):
            mod.verify(SAMPLE + "exit(dix_main(")

    def test_missing_global_rejected(self):
        with self.assertRaises(ValueError):
            mod.verify(SAMPLE.replace("static jobject thiz", "jobject other"))

    def test_unreviewed_cancel_rejected(self):
        with self.assertRaises(ValueError):
            mod.verify(SAMPLE + "pthread_cancel(t);")

    def test_source_drift_rejected(self):
        with self.assertRaises(ValueError):
            mod.verify(SAMPLE.replace("pthread_create(&t", "start_thread(&t"))

if __name__ == "__main__":
    unittest.main()
