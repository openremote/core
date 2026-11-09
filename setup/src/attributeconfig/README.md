# Attribute configuration demo setup

Seeds the master realm with one configured asset and three targets, to try exporting attribute
configuration from one asset and importing it into others.

## Running

Start the dev stack and run the manager with this setup:

```sh
docker compose -f profile/dev-testing.yml -p openremote up -d --no-build
OR_SETUP_TYPE=attributeconfig ./gradlew :setup:run
```

The setup only runs against a clean database; drop the `openremote-postgresql-data` volume to seed
it again.

## What it creates

| Asset                           | Type       | Purpose                                                                      |
| ------------------------------- | ---------- | ---------------------------------------------------------------------------- |
| `Pump 1 (configured)`           | PumpAsset  | Export from here; every attribute holds configuration items                  |
| `Pump 2 (import target)`        | PumpAsset  | Holds the same attributes without configuration, so everything imports       |
| `Pump 3 (partially compatible)` | PumpAsset  | Misses `errorCode` and holds `serialNumber` as a number, so both are skipped |
| `Mixer 1 (other asset type)`    | ThingAsset | Another asset type holding `flowRate` and `pressure`                         |

`Pump 1` carries a label, units, constraints, read only, public read, rule state, data point storage
and an agent link to `Pump Agent (Simulator)`, spread over its five attributes.

## Walkthrough

1. Open `Pump 1 (configured)`, switch to Modify and export its attribute configuration. All five
   attributes are offered, each listing its configuration items.
2. Open `Pump 2 (import target)` and import the file. Every attribute is importable; save the asset
   and its attributes now hold the configuration of `Pump 1`.
3. Import the same file into `Pump 3 (partially compatible)`. The report keeps the three descriptor
   attributes, skips `errorCode` because the asset does not have it, and skips `serialNumber`
   because its type differs.
4. Import the same file into `Mixer 1 (other asset type)`. The asset type mismatch is reported
   before the import, and only `flowRate` and `pressure` are applied.
5. Cancel an import at the warning step to confirm the draft is left untouched.

The agent link copied in step 2 points at the same agent instance as `Pump 1`, which is what generic
parameters will later make configurable per asset.
