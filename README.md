# lmcode Java shop sample

**Agent entry point:** this is a complete Java 21 host for the shared `lmcode` shop corpus, not another interpreter.
Read these two files first: [domain and bindings](src/main/java/io/lmctl/sample/Shop.java) and
[CLI protocol](src/main/java/io/lmctl/sample/Main.java). The Java SDK (`io.lmctl.lmcode`) is vendored
from `lmctl-ai/lmcode-java` with zero external Maven dependencies; the host needs only JDK 21, Maven,
Python 3 and `lmscript >= 0.2.420` (Node >= 24.15).

```sh
npm install --global @lmctl-ai/lmctl@0.2.420
./test.sh
```

Case 067 uses R47/R48 rule methods; reproducing its pinned bytecode needs a rule-methods-capable compiler,
so set `LMSCRIPT_BIN` to a current lmctl-mono `lmscript` build until a containing release is published
(see [sample-suite/README.md](sample-suite/README.md)). Runtime execution of the pinned bytecode works on
any compiler version, since annotations and interface bindings are compile-time metadata only.

## Write a host in Java in ten steps

1. **Choose a small domain.** `ShopState` owns three priced items (`[125, 250, 375]`); `Ledger` records
   entries; `Money` is signed `int64` cents (`long`). Read [the portable contract](sample-suite/CONTRACT.md).
2. **Declare the API.** [host.schema.json](sample-suite/host.schema.json) declares contexts (`shop`, `ledger`),
   four object types (`Money`, `Items`, `Shop`, `Ledger`), `Currency`, exact overloads, void methods and one SAM
   interface (`Adjuster`, `(Money) -> void`) under `interfaces`.
   [sample.dsl.json](sample-suite/sample.dsl.json) merges that domain with the standard catalog.
3. **Pin dependencies.** [sdk.lock](sdk.lock) and [suite.lock](suite.lock) hash every vendored file;
   `python3 scripts/sync.py check` rejects drift. [pom.xml](pom.xml) compiles `vendor/lmcode-java/src/main/java`
   alongside `src/main/java` and embeds `sample-suite/sample.dsl.json` as a classpath resource.
4. **Own objects before exposing handles.** [Shop.java](src/main/java/io/lmctl/sample/Shop.java) keeps `Money`
   instances in an arena (`List<Money>`) so every `Value.object("Money", m)` handle stays reachable for the
   entire run. Standard collections (`StrList`, `IntList`, `FloatList`, `StrMap`) are owned and cleared per
   run by the SDK registry arena.
5. **Register the schema's signatures.** [Shop.java](src/main/java/io/lmctl/sample/Shop.java) registers
   `context`, `function` and `method` callbacks on `Registry`. Distinct parameter lists register distinct
   overloads (`money(int)` in cents vs `money(float)` in units, rounded half away from zero). Enums cross
   the host boundary as strings (`"USD"`).
6. **Map language operations to methods.** Schema operators dispatch to `plus`, `minus`, `times` and `neg`;
   the declared conversion `int -> Money` calls `money(int)`. `Items.len()` and `Items.at(int)` enable
   `for (item in shop.items())`. `Ledger.record(Money)` and `Money.set(int)` return `self`; `Ledger.note(string)`
   returns `Value.none()`.
7. **Include standard and testing functions deliberately.** `new Registry()` installs the standard `string`,
   `encoding`, `math`, `collections`/`containers`, `json` and `file` catalogs; `Stdlib.registerTesting(registry, System.out)`
   opts into `print(string)` and `assert_close(float, float, float)`. The [coverage audit](sample-suite/audit.py)
   verifies that all 181 standard signatures and all 45 host signatures are imported by the corpus.
8. **Compile, never interpret source in the host.** [Program wrappers](sample-suite/programs) use
   `_lmcode_` and `savedsl`; compiled `.dslb` files are committed. `Program.load(bytecode, schema, registry)`
   verifies the exact schema SHA-256 and binds every import before execution.
9. **Handle errors at the right boundary.** [Main.java](src/main/java/io/lmctl/sample/Main.java) emits one
   UTF-8 LF JSON result line after any `print` trace lines: success `0`, CLI usage `2`, load/run error `3`,
   application failure `4`. A negative final ledger balance is an application rule checked after VM execution.
10. **Run the shared integration test.** [test.sh](test.sh) builds the executable JAR and wrapper script
    ([build.sh](build.sh)), audits signatures, runs [run-suite.sh](sample-suite/run-suite.sh), checks
    compiler bytecode reproducibility ([check-bytecode.py](scripts/check-bytecode.py)), and runs the negative
    harness controls ([test_runner.py](sample-suite/test_runner.py)). When GraalVM `native-image` is available,
    `RUN_NATIVE=1 ./test.sh` also builds and verifies a standalone native binary via
    [build-native.sh](scripts/build-native.sh).

## Conversions: `lmcode` type -> Java type

| `lmcode` type | Java / SDK representation | One-line rule |
|---|---|---|
| `int` | `long` (`Value.asInt()`, `Value.ofInt(long)`) | Signed 64-bit two's-complement integer; Java `+`, `-`, `*` and unary `-` wrap modulo $2^{64}$, while `/` and `%` by zero throw located `RUN_ERROR`. |
| `float` | `double` (`Value.asFloat()`, `Value.ofFloat(double)`) | IEEE-754 binary64 preserving `-0.0`, `NaN` and `±Infinity`; `str(float)` uses `Formatting.formatG15` (`%.15g` semantics, never `Double.toString`). |
| `bool` | `boolean` (`Value.asBool()`, `Value.ofBool(boolean)`) | Strict boolean (`true` or `false`); conditions and `!` reject non-boolean values. |
| `string` | `VmString` (`Value.asString()`, `Value.ofString(VmString)`) | **Immutable raw byte string (`byte[]`), not UTF-16 `String`:** arbitrary bytes and interior NULs round-trip unchanged; use `Reports.escapeBytes(VmString)` for byte-exact JSON output. |
| `Currency` (enum) | `VmString` / `String` (`Value.ofString("USD")`) | Enums cross the native host boundary as their schema member name string (`"USD"`, `"EUR"`). |
| `Money`, `Items`, `Shop`, `Ledger` | `Object` (`Value.object(type, obj)`, `Value.asObject()`) | Opaque host-owned Java reference tagged with its schema type name; `==` compares reference identity, not field equality. |
| `returns: "self"` | Receiver `Value` (`return self;`) | Normalized to the owner type in signatures (`Money.set`, `Ledger.record`) and returns the mutated receiver handle for chaining. |
| `void` | `Value.none()` (`Value.Kind.NONE`) | Returned by `void` functions and methods; the VM pushes nothing onto the operand stack. |
| `StrList`, `IntList`, `FloatList`, `StrMap` | `StdCollections` per-run arena (`Stdlib.collectionValues`, `Stdlib.mapEntries`, `Stdlib.make*`) | Created and invalidated automatically per `Program.run` by the SDK registry; expired handles throw `RUN_HOST`. |
| `IntMap`, `FloatMap`, `{Int,Float,Str}{Stack,Queue,PQ}` | `StdContainers` per-run arena (`StdCollections.obj`) | Insertion-ordered typed maps, LIFO stacks, FIFO queues (`len`/`at` in pop order) and stable min-first priority queues sharing the 65,536-object run budget. |
| `Json` (`json_parse`) | `StdJson.Node` in per-run arena | Immutable strict UTF-8 JSON (depth $\le 256$, `int64` integers, finite `binary64` floats, raw token spelling preserved in `to_json()`, borrowed child navigation). |
| Errors (`RUN_HOST`, `RUN_ERROR`, `LOAD_*`, `APP_FAIL`) | `VmException` (`VmException.host(msg)`, `Reports.error`, `Reports.appFailure`) | Host callbacks throw `VmException.host(msg)` (`RUN_HOST`, exit `3`) and the VM attaches `dsl line L:C: `; domain checks after `run()` call `Reports.appFailure(msg)` (`APP_FAIL`, exit `4`). |

## Feature coverage

| Feature | Programs |
|---|---|
| Primitive literals/arithmetic/comparison/logic/if | 001–004 |
| While, range, inline fn, switches | 005–008, 036, 039, 043, 045 |
| Host overloads, objects, operators, conversion, chaining, void methods | 009–014, 034–035, 040 |
| Pure host function `fee(int)` | 002 |
| Every std collection method | 015–018, 037–038, 101–104, 302–303, 312 |
| Every std math overload + all 18 intrinsic math operations | 019–020, 025, 048 |
| Every std string function, conversion and byte codec | 021–023, 301, 304–305, 311, 313 |
| Every std file function | 024 |
| Located VM/host errors, testing failure and application rule | 026–033 |
| int64 wrap/overflow, actual short-circuiting, host foreach | 041–044 |
| Raw/dedented inner heredocs and all four outer splice types | 046–047 |
| Native boundary conversions, 1 MiB strings, handles, 255 args, deep inline | 049–066 |
| `@NAME` rule method annotation and `implements` SAM interface | 067 |
| Bad magic/schema/import binding rejection | 090–092 |
| JSON navigation/types and typed map/stack/queue/priority queues | 101–108 |
| Compiler list/map/JSON literals (`%w`, `%i`, `%f`, `%{...}`, `%json{...}`) | 201–205 |
| Strict Base64/hex byte codecs and independent map `keys()`/`values()` snapshots | 301–305, 311–313 |

See [sample-suite/README.md](sample-suite/README.md) for links to every program source, compiled `.dslb`, and expected output.

```text
PASS SDK and suite locks
PASS: all 181 standard signatures are imported by the corpus
PASS: all 45 host signatures are imported by the corpus
PASS 2 compiler rejection case(s), exit 2
PASS host CLI protocol (0/2/3/4; schema/signatures/discovery)
PASS 91/91 sample cases
PASS compiler reproduces 91/91 pinned bytecode cases
```

## Running and updating

```sh
./build.sh                                                          # Build target/lmcode-java-sample.jar and build/lmcode-java-sample
./sample-suite/run-suite.sh java -jar target/lmcode-java-sample.jar # Run protocol + 91 cases directly
./test.sh                                                           # Full verification (locks, build, audit, 91 cases, bytecode, controls)
RUN_NATIVE=1 ./test.sh                                              # Also compile and verify GraalVM native-image binary
```

Canonical suite files live in `lmctl-ai/lmcode-cpp/sample-suite` and SDK sources in `lmctl-ai/lmcode-java`.
To resync after reviewed upstream commits:

```sh
./scripts/sync-sdk.sh ../lmcode-java
./scripts/sync-suite.sh ../lmcode-cpp
./test.sh
```
