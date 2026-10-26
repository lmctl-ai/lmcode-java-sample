package io.lmctl.sample;

import io.lmctl.lmcode.Context;
import io.lmctl.lmcode.Registry;
import io.lmctl.lmcode.Stdlib;
import io.lmctl.lmcode.Value;
import io.lmctl.lmcode.VmException;
import io.lmctl.lmcode.VmString;
import java.util.ArrayList;
import java.util.List;

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
        (ctx, self, a) -> Value.ofString("USD")); // Enums are string-backed at the host boundary.
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

  private static Money asMoney(Value value) {
    return (Money) value.asObject();
  }
}
