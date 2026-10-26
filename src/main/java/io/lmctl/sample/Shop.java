package io.lmctl.sample;

import io.lmctl.lmcode.Context;
import io.lmctl.lmcode.Registry;
import io.lmctl.lmcode.Stdlib;
import io.lmctl.lmcode.Value;
import io.lmctl.lmcode.VmException;
import io.lmctl.lmcode.VmString;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sample shop domain and lmcode-java bindings.
 *
 * <p>The host owns its domain objects; bytecode sees only schema type names and opaque
 * {@link Value#object(String, Object)} handles. Every handle allocated during a run stays
 * reachable in this host's arena until the process exits.
 */
public final class Shop {
  /** Signed int64 cents. All arithmetic wraps in 64-bit two's complement, matching Java long. */
  public static final class Money {
    public long cents;

    public Money(long cents) {
      this.cents = cents;
    }
  }

  /** Ordered collection of Money handles; exposes len() and at(int) for DSL foreach loops. */
  public static final class Items {
    public final List<Money> values = new ArrayList<>();
  }

  /** Root shop context holding the three default catalog items [125, 250, 375]. */
  public static final class ShopState {
    public final Items items = new Items();
  }

  /** Mutable ledger context tracking wrapped int64 balance, entry count, and raw byte-string note. */
  public static final class Ledger {
    public long balance = 0;
    public long entries = 0;
    public VmString note = new VmString(new byte[0]);
  }

  public final Registry registry = new Registry(); // Installs string/math/collections/file std by default.
  public final ShopState shop = new ShopState();
  public final Ledger ledger = new Ledger();
  private final List<Money> arena = new ArrayList<>();

  public Shop() {
    // Schema context names must match host.schema.json; context() binds live instances per run.
    registry.context("shop", "Shop").context("ledger", "Ledger");
    registerConversions(registry);
    Stdlib.registerTesting(registry, System.out); // Explicit opt-in for print(string) and assert_close.

    for (long cents : new long[] {125L, 250L, 375L}) {
      shop.items.values.add((Money) money(cents).asObject());
    }

    // Distinct parameter lists register distinct overloads. int means cents; float means units.
    registry.function("money", List.of("int"), "Money", (ctx, self, a) -> money(a[0].asInt()));
    registry.function("money", List.of("float"), "Money", (ctx, self, a) -> units(a[0].asFloat()));
    registry.function("fee", List.of("int"), "int", (ctx, self, a) -> Value.ofInt(a[0].asInt() + 5L));
    registry.function(
        "sample_tmp",
        List.of(),
        "string",
        (ctx, self, a) -> {
          String path = System.getenv("SAMPLE_TMP");
          if (path == null || path.isEmpty()) {
            throw VmException.host("SAMPLE_TMP is required");
          }
          return Value.ofString(path);
        });

    // Money methods and operator targets (+ -> plus, - -> minus, * / r* -> times, neg -> neg).
    registry.method(
        "Money", "amount", List.of(), "int", (ctx, self, a) -> Value.ofInt(asMoney(self).cents));
    registry.method(
        "Money",
        "currency",
        List.of(),
        "Currency",
        (ctx, self, a) -> {
          asMoney(self);
          return Value.ofString("USD"); // Enums are string-backed at the host boundary.
        });
    registry.method(
        "Money",
        "plus",
        List.of("Money"),
        "Money",
        (ctx, self, a) -> money(asMoney(self).cents + asMoney(a[0]).cents));
    registry.method(
        "Money",
        "minus",
        List.of("Money"),
        "Money",
        (ctx, self, a) -> money(asMoney(self).cents - asMoney(a[0]).cents));
    registry.method(
        "Money",
        "times",
        List.of("int"),
        "Money",
        (ctx, self, a) -> money(asMoney(self).cents * a[0].asInt()));
    registry.method(
        "Money", "neg", List.of(), "Money", (ctx, self, a) -> money(-asMoney(self).cents));
    registry.method(
        "Money",
        "set",
        List.of("int"),
        "Money",
        (ctx, self, a) -> {
          asMoney(self).cents = a[0].asInt();
          return self; // Schema returns:self normalizes to the owner type Money.
        });

    // Host collections need len() and at(int); foreach snapshots len() once before iterating.
    registry.method(
        "Items",
        "len",
        List.of(),
        "int",
        (ctx, self, a) -> Value.ofInt(((Items) self.asObject()).values.size()));
    registry.method(
        "Items",
        "at",
        List.of("int"),
        "Money",
        (ctx, self, a) -> {
          List<Money> values = ((Items) self.asObject()).values;
          long idx = a[0].asInt();
          if (idx < 0 || idx >= values.size()) {
            throw VmException.host("items: index out of range");
          }
          return Value.object("Money", values.get((int) idx));
        });

    // Shop methods.
    registry.method(
        "Shop",
        "items",
        List.of(),
        "Items",
        (ctx, self, a) -> Value.object("Items", ((ShopState) self.asObject()).items));
    registry.method(
        "Shop",
        "total",
        List.of(),
        "Money",
        (ctx, self, a) -> {
          long total = 0;
          for (Money item : ((ShopState) self.asObject()).items.values) {
            total += item.cents;
          }
          return money(total);
        });
    registry.method(
        "Shop", "price", List.of("int"), "Money", (ctx, self, a) -> money(a[0].asInt()));
    registry.method(
        "Shop", "price", List.of("float"), "Money", (ctx, self, a) -> units(a[0].asFloat()));
    registry.method(
        "Shop", "currency", List.of(), "Currency", (ctx, self, a) -> Value.ofString("USD"));
    registry.method(
        "Shop", "label", List.of(), "string", (ctx, self, a) -> Value.ofString("sample shop"));

    // Ledger methods: fluent mutation, overloaded void/getter note, and located host errors.
    registry.method(
        "Ledger",
        "record",
        List.of("Money"),
        "Ledger",
        (ctx, self, a) -> {
          Ledger target = (Ledger) self.asObject();
          target.balance += asMoney(a[0]).cents;
          target.entries += 1L;
          return self;
        });
    registry.method(
        "Ledger",
        "balance",
        List.of(),
        "Money",
        (ctx, self, a) -> money(((Ledger) self.asObject()).balance));
    registry.method(
        "Ledger",
        "count",
        List.of(),
        "int",
        (ctx, self, a) -> Value.ofInt(((Ledger) self.asObject()).entries));
    registry.method(
        "Ledger",
        "note",
        List.of("string"),
        "void",
        (ctx, self, a) -> {
          ((Ledger) self.asObject()).note = a[0].asString();
          return Value.none();
        });
    registry.method(
        "Ledger",
        "note",
        List.of(),
        "string",
        (ctx, self, a) -> Value.ofString(((Ledger) self.asObject()).note));
    registry.method(
        "Ledger",
        "reject",
        List.of("string"),
        "void",
        (ctx, self, a) -> {
          throw VmException.host(a[0].asString().toString()); // VM attaches DSL line and column.
        });
  }

  /** Creates a per-run execution context with live shop and ledger handles. */
  public Context context() {
    Context context = new Context();
    context.instructionLimit = Long.MAX_VALUE;
    context.memoryLimitBytes = Long.MAX_VALUE;
    context.setObject("shop", "Shop", shop);
    context.setObject("ledger", "Ledger", ledger);
    return context;
  }

  private Value money(long cents) {
    Money created = new Money(cents);
    arena.add(created);
    return Value.object("Money", created);
  }

  /** Converts unit floats to cents by rounding half away from zero, rejecting non-finite/out-of-range values. */
  private Value units(double amount) {
    double scaled = amount * 100.0;
    double cents = scaled >= 0.0 ? Math.floor(scaled + 0.5) : Math.ceil(scaled - 0.5);
    if (!Double.isFinite(cents) || cents < -0x1.0p63 || cents >= 0x1.0p63) {
      throw VmException.host("money: amount out of range");
    }
    return money((long) cents);
  }

  private static void registerConversions(Registry r) {
    r.function("host_int", List.of("int"), "int", (c, s, a) -> Value.ofInt(a[0].asInt()));
    r.function("host_float", List.of("float"), "float", (c, s, a) -> Value.ofFloat(a[0].asFloat()));
    r.function("host_bool", List.of("bool"), "bool", (c, s, a) -> Value.ofBool(a[0].asBool()));
    r.function("host_bytes", List.of("string"), "string", (c, s, a) -> Value.ofString(a[0].asString()));
    r.function(
        "host_hex",
        List.of("string"),
        "string",
        (c, s, a) -> Value.ofString(HexFormat.of().formatHex(a[0].asString().bytes())));
    r.function(
        "host_float_case",
        List.of("string"),
        "float",
        (c, s, a) ->
            switch (a[0].asString().toString()) {
              case "-0" -> Value.ofFloat(-0.0);
              case "nan" -> Value.ofFloat(Double.NaN);
              case "inf" -> Value.ofFloat(Double.POSITIVE_INFINITY);
              case "-inf" -> Value.ofFloat(Double.NEGATIVE_INFINITY);
              case "large" -> Value.ofFloat(1e21);
              case "subnormal" -> Value.ofFloat(Double.MIN_VALUE);
              default -> throw VmException.host("host_float_case: unknown case");
            });
    r.function("host_echo_money", List.of("Money"), "Money", (c, s, a) -> a[0]);
    r.function(
        "host_same",
        List.of("Money", "Money"),
        "bool",
        (c, s, a) -> Value.ofBool(a[0].asObject() == a[1].asObject()));
    r.function("host_null", List.of(), "Money", (c, s, a) -> Value.object("Money", null));
    r.function(
        "host_is_null",
        List.of("Money"),
        "bool",
        (c, s, a) -> Value.ofBool(a[0].asObject() == null));
    r.function("host_wrong", List.of(), "Money", (c, s, a) -> Value.object("Items", null));
    r.function("host_void", List.of(), "void", (c, s, a) -> Value.none());
    r.function(
        "host_str_list",
        List.of("StrList"),
        "StrList",
        (c, s, a) -> {
          List<VmString> items = new ArrayList<>();
          for (Value v : Stdlib.collectionValues(a[0])) items.add(v.asString());
          items.add(VmString.fromString("host"));
          return Stdlib.makeStrList(c, items);
        });
    r.function(
        "host_int_list",
        List.of("IntList"),
        "IntList",
        (c, s, a) -> {
          List<Long> items = new ArrayList<>();
          for (Value v : Stdlib.collectionValues(a[0])) items.add(v.asInt());
          items.add(9L);
          return Stdlib.makeIntList(c, items);
        });
    r.function(
        "host_float_list",
        List.of("FloatList"),
        "FloatList",
        (c, s, a) -> {
          List<Double> items = new ArrayList<>();
          for (Value v : Stdlib.collectionValues(a[0])) items.add(v.asFloat());
          items.add(0.5);
          return Stdlib.makeFloatList(c, items);
        });
    r.function(
        "host_str_map",
        List.of("StrMap"),
        "StrMap",
        (c, s, a) -> {
          Map<VmString, VmString> entries = new LinkedHashMap<>(Stdlib.mapEntries(a[0]));
          entries.put(VmString.fromString("host"), VmString.fromString("yes"));
          return Stdlib.makeStrMap(c, entries);
        });
    r.function(
        "host_sum",
        Collections.nCopies(255, "int"),
        "int",
        (c, s, a) -> {
          long sum = 0;
          for (Value v : a) sum += v.asInt();
          return Value.ofInt(sum);
        });
    r.function(
        "host_long_" + "x".repeat(128),
        List.of("int"),
        "int",
        (c, s, a) -> Value.ofInt(a[0].asInt()));
  }

  private static Money asMoney(Value value) {
    Money m = (Money) value.asObject();
    if (m == null) {
      throw VmException.host("money: null handle");
    }
    return m;
  }
}
