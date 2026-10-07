#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import unittest
spec = importlib.util.spec_from_file_location("capability", Path(__file__).with_name("linux-capability.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class CapacityTests(unittest.TestCase):
    def test_standard_capacity_passes(self):
        self.assertTrue(module.assess(4, 10000, 10000)["pass"])
    def test_cpu_overcommit_refused(self):
        self.assertFalse(module.assess(2, 10000, 10000)["pass"])
    def test_memory_includes_observers_and_reserve(self):
        self.assertFalse(module.assess(4, 5000, 10000)["pass"])
        r = module.assess(4, 10000, 10000)
        c = r["caps"]
        total = (2*c["apps"]["memory_mib_each"] + c["mysql"]["memory_mib"] +
                 2*c["redis"]["memory_mib_each"] + c["generator"]["memory_mib"] +
                 c["observers"]["memory_budget_mib"] + c["host_reserve"]["memory_budget_mib"])
        self.assertEqual(r["required_memory_mib"], total)
    def test_disk_reserve_refused(self):
        self.assertFalse(module.assess(4, 10000, 1000)["pass"])

if __name__ == "__main__":
    unittest.main()
