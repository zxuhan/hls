# experiments/

Standalone Python re-implementation of the four scheduling algorithms
(`A_MTS`, `A_SPT`, `B_CPSAT`, `C_ENHANCED`) used in the thesis. No Spring
Boot, no JVM, no REST — just `python scripts/multi_instance_run.py`.

The Java backend in `../backend/` is the company-facing application and
is **independent** of this folder. The two share only the Excel data
schema (see `../DATA_SCHEMA.md`).

## Layout

```
experiments/
  algorithms/           # pure-Python scheduling library
    models.py           # Block, Shift, ShiftDay, ScheduledBlock, ScheduleResult
    loader.py           # Excel reader (Blocks / TDM / PDM sheets)
    timeline.py         # half-hour timeline + FTE capacity helpers
    precedence.py       # cycle detection, total-successors, CPR
    placement.py        # greedy placement engine + 6 hard constraints
    greedy.py           # A_MTS, A_SPT
    enhanced.py         # C_ENHANCED with CPR + OPA + CST (gamma toggleable)
    cpsat.py            # B_CPSAT via ortools.sat.python.cp_model
  scripts/
    instance_features.py    # structural features per instance -> CSV
    multi_instance_run.py   # 15-row experiment per instance -> CSV
    aggregate_results.py    # combine instance CSVs, compute H1a/b/c
  data/                 # put instance Excel files here (gitignored)
  results/              # CSVs land here (tracked in git)
```

## Setup

Requires Python 3.10+.

```sh
cd experiments
python3 -m venv .venv
source .venv/bin/activate          # Windows: .venv\Scripts\activate
pip install -r requirements.txt
```

## Instances and naming

**One Excel workbook = one instance = one ASML machine module.** Each
file contains the full `Blocks` + `TDM` + `PDM` triple for that
module — a complete, independent scheduling problem.

The two arguments are deliberately decoupled:
* `--data` is the **filename on disk** (looked up under `data/` if you
  pass a bare name; full/relative paths also work).
* `--instance-id` is the **stable label** that lands in every CSV row
  and every thesis table. Use this when referring to the instance in
  analysis, prose, or table headings.

Convention used throughout this repo:

| File on disk           | Instance id | Notes                                  |
|------------------------|-------------|----------------------------------------|
| `data/data_one.xlsx`   | `MOD_M1`    | First instance (the one already in the repo) |
| `data/data_two.xlsx`   | `MOD_A`     | First Windows-laptop instance          |
| `data/data_three.xlsx` | `MOD_B`     | Second Windows-laptop instance         |
| `data/data_four.xlsx`  | `MOD_C`     | …continue alphabetically               |

Result CSVs are keyed on the `instance_id`, so the same dataset is
always reported under the same label even if the on-disk filename
changes between machines.

## Running the experiments

Place an instance Excel in `data/`, then:

```sh
# Compute structural features
python scripts/instance_features.py --data data_two.xlsx --instance-id MOD_A

# Run all 15 algorithm configurations
python scripts/multi_instance_run.py --data data_two.xlsx --instance-id MOD_A
```

Both write to `results/` with stable filenames keyed on `--instance-id`
(e.g. `results/run_MOD_A.csv`). To re-run the same instance, pass the
same `--instance-id` and the script will overwrite the old row in the
features CSV and produce a new run file.

To collect the per-instance CSVs into the H1a/H1b/H1c summary:

```sh
python scripts/aggregate_results.py
```

## Default configuration

| Setting | Value | Why |
|---|---|---|
| Shift schedule | 14 h × 6 FTE × 30 days | Generous horizon so infeasibility means *algorithm* infeasibility, not horizon-too-short |
| `B_CPSAT` time limit | 600 s | Safety net; real solves end in <1 s on the studied instances |
| `B_CPSAT` runs per instance | 3 | Variance characterisation |
| `C_ENHANCED` weight grid | {0, 0.25, 0.5, 0.75, 1.0} | Same five points used in the original draft |
| `C_ENHANCED` γ grid | {0.0, 0.5} | γ=0 disables CST; γ=0.5 is the default boost — supports H1c paired comparison |

Override per-script via flags; see `--help`.
