import importlib.util
from pathlib import Path
import unittest

path = Path(__file__).with_name("patch-m8-x11-no-tcp.py")
spec = importlib.util.spec_from_file_location("m8_source_patch", path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class SourcePatchTest(unittest.TestCase):
    def upstream(self):
        return (
            'void boot() {\n'
            + module.NATIVE_EXIT + '\n'
            + '}\n'
            + module.START + '\n'
            + 'void lorieListenForKnocks(int port, const char *tag) {\n'
            + 'struct sockaddr_in address = { .sin_addr = { .s_addr = INADDR_ANY } };\n'
            + 'socket(AF_INET, SOCK_STREAM, 0);\n'
            + 'SetNotifyFd(fd, nullptr, 0, nullptr);\n'
            + 'sendBroadcastDelayed();\n'
            + '}\n'
            + module.END + '\n'
        )

    def test_patch_removes_tcp_listener_and_process_exit(self):
        result = module.patch(self.upstream())
        self.assertNotIn("socket(AF_INET", result)
        self.assertNotIn("INADDR_ANY", result)
        self.assertNotIn(module.NATIVE_EXIT, result)
        self.assertIn("return nullptr;", result)
        self.assertIn("void lorieListenForKnocks", result)

    def test_unrecognized_or_double_patched_source_rejected(self):
        with self.assertRaises(ValueError):
            module.patch("not pinned upstream")
        with self.assertRaises(ValueError):
            module.patch(module.patch(self.upstream()))

if __name__ == "__main__":
    unittest.main()
