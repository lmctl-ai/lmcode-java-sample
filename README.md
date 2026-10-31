# lmcode Java shop sample

**Agent entry point:** this is a complete Java 21 host for the shared `lmcode` shop corpus, not another interpreter.
Read these two files first: [domain and bindings](src/main/java/io/lmctl/sample/Shop.java) and
[CLI protocol](src/main/java/io/lmctl/sample/Main.java). The Java SDK (`io.lmctl.lmcode`) is vendored
from `lmctl-ai/lmcode-java` with zero external Maven dependencies; the host needs only JDK 21, Maven,
Python 3 and a v6-entries-capable `lmscript` (Node >= 24.15).

```sh
LMSCRIPT_BIN=/path/to/v6-entries-capable/lmscript ./test.sh
```

The corpus is lmcode bytecode (DSLB v6) (R49 rule entries), first published as `@lmctl-ai/lmctl` 0.2.444, which reproduces
every pinned `.dslb` byte-for-byte; 0.2.442/0.2.443 accept the rule syntax but still write v5. Set
`LMSCRIPT_BIN` to a development lmscript build only when testing unreleased compiler work
(see [sample-suite/README.md](sample-suite/README.md)). The vendored v6 SDK runs the pinned bytes directly
and refuses v5 files at load (093).

## Write a host in Java in ten steps

1. **Choose a small domain.** `ShopState` owns three priced items (`[125, 250, 375]`); `Ledger` records
   entries; `Money` is signed `int64` cents (`long`). Read [the portable contract](sample-suite/CONTRACT.md).
2. **Declare the API.** [host.schema.json](sample-suite/host.schema.json) declares contexts (`shop`, `ledger`),
   four object types (`Money`, `Items`, `Shop`, `Ledger`), `Currency`, exact overloads, void methods and two SAM
   interfaces (`Adjuster`, `(Money) -> void`; `Score`, `(Money) -> int`) under `interfaces`.
   [`sample.dsl.json`](sample-suite/sample.dsl.json) merges that domain with the standard catalog.
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
   opts into `print(string)` and `assert_close(float, float, float)`. The [coverage audit](sample-suite/audit.lms)
   verifies that all 182 standard signatures and all 45 host signatures are imported by the corpus.
8. **Compile, never interpret source in the host.** [Program wrappers](sample-suite/programs) use
   `_lmcode_` with `out=`; compiled `.dslb` files are committed. `Program.load(bytecode, schema, registry)`
   verifies the exact schema SHA-256 and binds every import before execution.
9. **Handle errors at the right boundary.** [Main.java](src/main/java/io/lmctl/sample/Main.java) emits one
   UTF-8 LF JSON result line after any `print` trace lines: success `0`, CLI usage `2`, load/run error `3`,
   application failure `4`. After the body runs, the host calls the program's rule entries
   (`Program.entries()` / `Program.call(...)`): each `@item` entry once per shop item in order, each
   `@total` entry once with the shop total, others never; non-void results print `entry NAME = VALUE`
   lines and the result object reports the call count as `"entries_called"`. A negative final ledger
   balance is an application rule checked after VM execution.
10. **Run the shared integration test.** [test.sh](test.sh) builds the executable JAR and wrapper script
    ([build.sh](build.sh)), audits signatures, runs [run-suite.sh](sample-suite/run-suite.sh), checks
    compiler bytecode reproducibility ([check-recompile.lms](sample-suite/check-recompile.lms)), and runs the negative
    harness controls ([test-runner.lms](sample-suite/test-runner.lms)). When GraalVM `native-image` is available,
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
| Rule entries called by the host after the body (`@item`/`@total`, `entries_called`) | 068–069 |
| Bad magic/schema/import binding rejection, v5 bytecode refused | 090–093 |
| JSON navigation/types and typed map/stack/queue/priority queues | 101–108 |
| Compiler list/map/JSON literals (`%w`, `%i`, `%f`, `%{...}`, `%json{...}`) | 201–205 |
| Strict Base64/hex byte codecs and independent map `keys()`/`values()` snapshots | 301–305, 311–313 |

See [sample-suite/README.md](sample-suite/README.md) for links to every program source, compiled `.dslb`, and expected output.

```text
PASS SDK and suite locks
PASS: all 182 standard signatures are imported by the corpus
PASS: all 45 host signatures are imported by the corpus
PASS 3 compiler rejection case(s), exit 2
PASS host CLI protocol (0/2/3/4; schema/signatures/discovery)
PASS 108/108 sample cases
PASS compiler reproduces 108/108 pinned bytecode cases
```

## Running and updating

```sh
./build.sh                                                          # Build target/lmcode-java-sample.jar and build/lmcode-java-sample
./sample-suite/run-suite.sh java -jar target/lmcode-java-sample.jar # Run protocol + 108 cases directly
./test.sh                                                           # Full verification (locks, build, audit, 108 cases, bytecode, controls)
RUN_NATIVE=1 ./test.sh                                              # Also compile and verify GraalVM native-image binary
```

Canonical suite files live in `lmctl-ai/lmcode-cpp/sample-suite` and SDK sources in `lmctl-ai/lmcode-java`.
To resync after reviewed upstream commits:

```sh
./scripts/sync-sdk.sh ../lmcode-java
./scripts/sync-suite.sh ../lmcode-cpp
./test.sh
```

## lmscript tests and automatic native releases

```sh
npm install -g @lmctl-ai/lmctl
lmctl test.lms
# Equivalent: lmscript test.lms
# With GraalVM 21 and native compiler tools on PATH:
RUN_NATIVE=1 lmscript --no-preflight test.lms
```

Both executables run `.lms` files; `lmctl` also accepts URLs ending in `.lms`.
These tests require the checkout and run from its root. Python is used only
for lock validation/synchronization; test orchestration uses the canonical
lmscript drivers. Node supplies the byte-preserving process adapter.

[examples/rule-entries.lms](examples/rule-entries.lms) demonstrates `@item`
and `@total` functions implementing `Adjuster(Money) -> void`, plus an
undispatched `@never` entry. Tests compile it and compare the exact trace.
The canonical program 069 demonstrates an interface returning a value.

Every push to `main` builds with GraalVM Community 21 on Linux x64/arm64,
macOS x64/arm64 and Windows x64. Each job tests the JVM host and all 108
cases against the native executable. All five must pass before an automatic
`v0.4.<workflow run number>` release is published with checksums and notices.
Pull requests run the same checks without publishing. Native executables
require no JVM; Linux targets glibc and Windows requires the MSVC runtime.

```sh
lmscript install github:lmctl-ai/lmcode-java-sample@latest
```

## Text templates

[examples/text-template.lms](examples/text-template.lms) renders ordinary text from a nested context with indexed lists, loops and boolean conditions. The standard `template_render(string, Json) -> string` function performs one pass; inserted values stay literal. Compile it with `lmscript --no-preflight examples/text-template.lms build/text-template.dslb`, then run the sample host with `--lmcode build/text-template.dslb`.

The lmscript-driven suite runs 108 cases, including template success/error cases. See the [portable template contract](https://github.com/lmctl-ai/lmcode-cpp/blob/main/std/template.md).

## Historical literal names

The documentation calls the language lmcode. `--lmcode`, `--lmcode-schema` and `--lmcode-signatures` are current host flag literals; `DSLB` is the bytecode magic and `DSLB v6` is the current format name; `.dslb` is the current bytecode filename extension. Other code-formatted API names, paths, manifest names and diagnostic text retain their current literal spellings.

## Invocation result

The host retains one SDK `ResultSink` across the body and selected rule entries, then writes a terminal return frame only after successful domain validation. Unset results produce `null`. The canonical suite has 123 cases. Use an `LMSCRIPT_BIN` built from mono main with the renamed host flags and `out=` support; older published compilers do not implement this contract.
