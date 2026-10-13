import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from audit_kvpredicate_prhist import audit
from kvpredicate_trace_to_prhist import ConversionError, convert, iter_jsonl, predicate_to_query


class KvPredicateTraceToPrhistTest(unittest.TestCase):
    def test_conversion_preserves_open_range_predicate_results(self):
        rows = [
            {
                "record_type": "initial",
                "key": f"kv:{index}",
                "value": value,
                "semantic": value,
                "row": {"k": f"k{index}", "value": value},
            }
            for index, value in enumerate((90, 91, 99, 100))
        ]
        rows.extend([
            {
                "record_type": "txn",
                "txn": 10,
                "session": 0,
                "session_seq": 1,
                "status": "commit",
                "ops": [{
                    "op_index": 0,
                    "type": "pr",
                    "predicate": {"kind": "range", "lower": 90, "upper": 100},
                    "results": [
                        {"key": "kv:1", "value": 91, "semantic": 91},
                        {"key": "kv:2", "value": 99, "semantic": 99},
                    ],
                    "read_versions": [
                        {"key": "kv:1", "value": 91},
                        {"key": "kv:2", "value": 99},
                    ],
                }],
            },
            {
                "record_type": "txn",
                "txn": 11,
                "session": 0,
                "session_seq": 2,
                "status": "commit",
                "ops": [{
                    "op_index": 0,
                    "type": "pr",
                    "predicate": {"kind": "range", "lower": 100, "upper": 101},
                    "results": [],
                    "read_versions": [],
                }],
            },
        ])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw = root / "raw.jsonl"
            raw.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
            case = root / "case" / "hist-00000"

            manifest = convert(raw, case)
            report = audit(case)
            history = list(iter_jsonl(case / "history.prhist.jsonl.zst"))

            self.assertTrue(report["valid"])
            self.assertEqual(manifest["predicate_reads"], 2)
            self.assertEqual(report["predicate_reads"], 2)
            self.assertEqual(history[0]["ops"][0]["query"]["where"], ["value > 90", "value < 100"])
            self.assertEqual(history[0]["ops"][0]["result"], {
                "values": [{"k": "1", "value": 91}, {"k": "2", "value": 99}],
                "inputs": [{"key": "kv:1", "value": 91}, {"key": "kv:2", "value": 99}],
            })
            self.assertEqual(history[1]["ops"][0]["query"]["where"], ["value > 100", "value < 101"])
            self.assertEqual(history[1]["ops"][0]["result"], {"values": [], "inputs": []})

    def test_conversion_rejects_invalid_range_bounds(self):
        cases = [
            ({"kind": "range", "lower": 90, "upper": 90}, "lower must be less than upper"),
            ({"kind": "range", "lower": 100, "upper": 90}, "lower must be less than upper"),
            ({"kind": "range", "upper": 100}, r"predicate\.lower: expected integer"),
            ({"kind": "range", "lower": 90}, r"predicate\.upper: expected integer"),
        ]
        for predicate, expected_error in cases:
            with self.subTest(predicate=predicate):
                with self.assertRaisesRegex(ConversionError, expected_error):
                    predicate_to_query(predicate, "raw.txn[0].ops[0]")

    def test_conversion_records_injected_write_skew_metadata(self):
        rows = [
            {
                "record_type": "initial",
                "key": f"kv:{key}",
                "value": key,
                "semantic": key,
                "row": {"k": f"k{key}", "value": key},
            }
            for key in range(5)
        ]
        rows.extend(
            [
                {
                    "record_type": "txn",
                    "txn": 10,
                    "session": 0,
                    "session_seq": 1,
                    "txn_type": (
                        "Txn|kvpredicate-anomaly:write-skew:injected:left:17:true"
                    ),
                    "status": "commit",
                    "ops": [
                        {
                            "op_index": 0,
                            "type": "pr",
                            "predicate": {"kind": "eq", "value": 0},
                            "results": [
                                {"key": "kv:0", "key_id": 0, "value": 0, "semantic": 0}
                            ],
                            "read_versions": [
                                {"key": "kv:0", "key_id": 0, "value": 0, "semantic": 0}
                            ],
                        },
                        {
                            "op_index": 1,
                            "type": "w",
                            "key": "kv:1",
                            "value": 5,
                            "semantic": 5,
                        },
                    ],
                },
                {
                    "record_type": "txn",
                    "txn": 11,
                    "session": 1,
                    "session_seq": 1,
                    "txn_type": (
                        "Txn|kvpredicate-anomaly:write-skew:injected:right:17:true"
                    ),
                    "status": "commit",
                    "ops": [
                        {
                            "op_index": 0,
                            "type": "pr",
                            "predicate": {"kind": "eq", "value": 1},
                            "results": [
                                {"key": "kv:1", "key_id": 1, "value": 1, "semantic": 1}
                            ],
                            "read_versions": [
                                {"key": "kv:1", "key_id": 1, "value": 1, "semantic": 1}
                            ],
                        },
                        {
                            "op_index": 1,
                            "type": "w",
                            "key": "kv:0",
                            "value": 6,
                            "semantic": 6,
                        },
                    ],
                },
            ]
        )

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw = root / "raw.jsonl"
            raw.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
            manifest = convert(raw, root / "case" / "hist-00000")

            anomaly = manifest["anomaly_injection"]
            self.assertEqual(anomaly["mode"], "write-skew")
            self.assertEqual(anomaly["variant"], "injected")
            self.assertEqual(anomaly["layout_seed"], 17)
            self.assertTrue(anomaly["background_predicates_isolated"])
            self.assertTrue(anomaly["expected_cycle"])
            self.assertEqual(
                [(edge["from_txn"], edge["to_txn"], edge["key"])
                 for edge in anomaly["expected_dependency_edges"]],
                [(10, 11, "kv:0"), (11, 10, "kv:1")],
            )
            self.assertEqual(
                [(txn["role"], txn["txn"], txn["write_key"])
                 for txn in anomaly["core_transactions"]],
                [("left", 10, "kv:1"), ("right", 11, "kv:0")],
            )

    def test_conversion_preserves_kv_predicate_workload(self):
        rows = [
            {
                "record_type": "initial",
                "key": "kv:0",
                "value": 0,
                "semantic": 0,
                "row": {"k": "k0", "value": 0},
            },
            {
                "record_type": "initial",
                "key": "kv:1",
                "value": 1,
                "semantic": 1,
                "row": {"k": "k1", "value": 1},
            },
            {
                "record_type": "txn",
                "txn": 10,
                "session": 0,
                "session_seq": 1,
                "txn_type": "Txn",
                "status": "commit",
                "ops": [
                    {
                        "op_index": 0,
                        "type": "r",
                        "key": "kv:0",
                        "value": 0,
                        "semantic": 0,
                    },
                    {
                        "op_index": 1,
                        "type": "w",
                        "key": "kv:1",
                        "value": 2,
                        "semantic": 2,
                        "before_value": 1,
                        "new_row": {"k": "k1", "value": 2},
                    },
                ],
            },
            {
                "record_type": "txn",
                "txn": 11,
                "session": 0,
                "session_seq": 2,
                "txn_type": "Txn",
                "status": "commit",
                "ops": [
                    {
                        "op_index": 0,
                        "type": "pr",
                        "predicate": {"kind": "mod", "modulus": 2, "target": 0},
                        "results": [
                            {"key": "kv:0", "key_id": 0, "value": 0, "semantic": 0},
                            {"key": "kv:1", "key_id": 1, "value": 2, "semantic": 2},
                        ],
                        "read_versions": [
                            {"key": "kv:0", "key_id": 0, "value": 0, "semantic": 0},
                            {"key": "kv:1", "key_id": 1, "value": 2, "semantic": 2},
                        ],
                    }
                ],
            },
            {
                "record_type": "txn",
                "txn": 12,
                "session": 0,
                "session_seq": 3,
                "txn_type": "Txn",
                "status": "commit",
                "ops": [
                    {
                        "op_index": 0,
                        "type": "r",
                        "key": "kv:11",
                        "value": -1000000000011,
                        "semantic": -1,
                        "absent": True,
                    }
                ],
            },
            {
                "record_type": "txn",
                "txn": 13,
                "session": 0,
                "session_seq": 4,
                "txn_type": "Txn",
                "status": "commit",
                "ops": [
                    {
                        "op_index": 0,
                        "type": "pr",
                        "predicate": {"kind": "gt", "value": 0},
                        "results": [
                            {"key": "kv:1", "key_id": 1, "value": 2, "semantic": 2},
                        ],
                        "read_versions": [
                            {"key": "kv:1", "key_id": 1, "value": 2, "semantic": 2},
                        ],
                    }
                ],
            },
            {
                "record_type": "txn",
                "txn": 14,
                "session": 0,
                "session_seq": 5,
                "txn_type": "Txn",
                "status": "commit",
                "ops": [
                    {
                        "op_index": 0,
                        "type": "pr",
                        "predicate": {"kind": "lt", "value": 2},
                        "results": [
                            {"key": "kv:0", "key_id": 0, "value": 0, "semantic": 0},
                        ],
                        "read_versions": [
                            {"key": "kv:0", "key_id": 0, "value": 0, "semantic": 0},
                        ],
                    }
                ],
            },
        ]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw = root / "raw.jsonl"
            raw.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
            case = root / "kvpredicate_smoke" / "hist-00000"

            manifest = convert(raw, case)
            report = audit(case)
            history = list(iter_jsonl(case / "history.prhist.jsonl.zst"))
            initial = json.loads((case / "initial_state.json").read_text(encoding="utf-8"))

            self.assertEqual(manifest["case_kind"], "real_postgresql_kvpredicate")
            self.assertEqual(manifest["files"]["history"], "history.prhist.jsonl.zst")
            self.assertFalse((case / "history.prhist.jsonl").exists())
            self.assertTrue(report["valid"])
            self.assertEqual(history[1]["ops"][0]["query"]["where"], ["value % 2 = 0"])
            self.assertEqual(history[1]["ops"][0]["result"]["values"], [{"k": "0", "value": 0}, {"k": "1", "value": 2}])
            self.assertEqual(history[3]["ops"][0]["query"]["where"], ["value > 0"])
            self.assertEqual(history[4]["ops"][0]["query"]["where"], ["value < 2"])
            self.assertIn({"absent": True, "key": "kv:11", "value": -1000000000011}, initial)

    def test_conversion_rejects_source_provenance_fields(self):
        rows = [
            {
                "record_type": "initial",
                "key": "kv:0",
                "value": 0,
                "semantic": 0,
                "source_write_id": 0,
                "row": {"k": "k0", "value": 0},
            },
        ]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw = root / "raw.jsonl"
            raw.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")

            with self.assertRaisesRegex(ConversionError, "source provenance"):
                convert(raw, root / "case" / "hist-00000")


if __name__ == "__main__":
    unittest.main()
