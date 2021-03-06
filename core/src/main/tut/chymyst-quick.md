<link href="{{ site.github.url }}/tables.css" rel="stylesheet" />

# Programming the Chemical Machine: Quick start

`Chymyst Core` implements a declarative DSL for purely functional concurrency in Scala.
The DSL is based on the "chemical machine" paradigm, which is likely unfamiliar to many readers. 

This chapter is for the impatient readers who want to dive straight into the code, with very few explanations.

Read the next chapter if you prefer to understand the concepts more fully before looking at code.

## Setup

First, declare this library dependency in your `build.sbt`:

```scala
libraryDependencies += "io.chymyst" %% "chymyst-core" % "latest.integration"

```

The `Chymyst Core` DSL becomes available once you add this statement:

```tut:silent
import io.chymyst.jc._
```

This imports all the necessary symbols such as `m`, `b`, `site`, `go` and so on.

## Reactions and molecules, as carriers of processes and data

In the chemical machine, any concurrently running process (called a **reaction**) is implemented as
a computation that works with a special kind of data called **molecules**.

A reaction consumes one or more input molecules and may emit (zero or more) new molecules.
Each molecule carries a "payload" value, and reactions can perform computations with these values.

### Molecule emitters

Molecules are created out of ordinary data values by calling special **molecule emitters**.

All molecule emitters must be declared before using them.
A new molecule emitter is created using the special syntax `m[T]`, where `T` is the type of the value:

```tut
val c = m[Int] // Emitter for molecule `c` with payload value of type `Int`.

val in = m[Int] // Emitter for molecule `in` with `Int` payload value.

val result = m[Int] // Emitter for molecule `result` with `String` payload value.

```

Molecules can be emitted using this syntax:

```scala
val c = m[Int] // Emitter for molecule `c` with payload value of type `Int`.
c(123) // Emit a new molecule `c()` carrying the payload value `123` of type `Int`.
```

So, a molecule can be seen as a data value together with a special "chemical" label (represented by the emitter).
We may say that "a molecule `c` carries the payload value `123`".

The result of evaluating `c(123)`, — that is, the result of calling a molecule emitter `c` with data value `123`, -
is to emit a new copy of a molecule `c` that carries the value `123` as its payload.

### Declaring and activating reactions

A reaction must be declared using the `go { }` syntax.
The input molecules are defined via pattern-matching, and the pattern variables match the values carried by the input molecules.
The body of a reaction is an arbitrary Scala expression that may depend on the payload values of the input molecules, and may emit new molecules:

```tut
val r = go { case c(x) + in(y) ⇒ c(x + y) }

```

Reactions are _locally scoped_ values, as are molecule emitters. Creating them does not yet start any processes.
Molecules cannot be emitted and reactions will not start until they are "activated".

In order to activate the declared reactions, use the `site()` call. This creates a **reaction site**.

```tut
val r1 = go { case in(x) ⇒     // Consume a molecule `in(...)` as input.
                               // Now declare the body of the reaction:
           val z = x * 2       // Compute some new value using the value `x`.
           result(z)           // Emit a new molecule `result(z)`.
        }
val r2 = go { case result(x) ⇒ println(x) } // Consume `result(...)` and perform a side effect.
site(r1, r2) // Create and activate a reaction site containing these two reactions.

in(123); in(124); in(125)   // Emit some initial molecules.
Thread.sleep(200) // Wait for reactions to start and run.

```

Emitters can be called many times to emit many copies of a molecule:

```scala
in(0); in(0); in(0)
(1 to 10).foreach(x ⇒ in(x))

```

All emitted molecules become available for reactions to consume them.
Reactions will start automatically, whenever their required input molecules become available (i.e. are emitted).
Until then, all emitted molecules are stored at the reaction site and wait there.

Emitting a molecule is a _non-blocking_ operation; execution continues immediately, without waiting for any reactions to start.
Reactions will start as soon as possible and will run in parallel with the processes that emitted their input molecules.

### Example: Running several reactions in parallel

A reaction can depend on _several_ input molecules at once, and may emit several molecules as output.
The actual computation will start only when _all_ its input molecules are available (have been emitted and not yet consumed by other reactions).

In this example, we will start a reaction that will emit two molecules that, in turn, will start two parallel reactions.

```tut
val in1 = m[Int] // Molecule `in1` with value of type `Int`.
val in2 = m[Int] // Molecule `in2`.

val result = m[Boolean] // Molecule `result` with value of type `Boolean`.

site(
  go { case in1(x) + in2(y) ⇒        // Wait for two molecules.
    println(s"Got x = $x, y = $y.")  // Some debug output.
    val z: Boolean = x != y          // Compute a new value `z`.
    result(z)                        // Emit `result` molecule with value `z`.
    val t: Boolean = x > y           // Another computation, whatever.
    result(t)                        // Emit another `result` molecule.
    println(s"Emitted result($z) and result($t).")
  },
  go { case result(x) ⇒ println(s"got result = $x") }
)
in2(20)
in1(10)             // Emit initial molecules.
Thread.sleep(200)   // Wait for reactions to run.

```

### Example: Asynchronous continuations

Once a molecule emitter is declared, the type of the molecule's payload value is statically fixed.
This type can be any Scala type, such as `Int`, `(Double, Double)`, `Option[Seq[Int]]`, a custom class, a function type such as `Int ⇒ Boolean`, etc.

Using molecules with a payload of _function type_ will allow us to implement **asynchronous continuations**:

```tut
val in = m[Int] // Input molecule.

val cont = m[Int ⇒ Unit]  // Molecule that carries the continuation as payload.

site(
  go { case in(x) + cont(k) ⇒
    println(s"Got x = $x.")
    val z : Int = x * x   // Compute some output value.
    k(z)                  // Invoke continuation.
  }
)

in(100) // Emit a first molecule, `in`.
        // Now emit the second molecule, `cont`, required by reaction.
cont(i ⇒ println(s"Computed result = $i."))
Thread.sleep(200)

```

New reactions and molecules can be defined anywhere in the code, -
for instance, within a function scope or within the local scope of another reaction's body.

### What a Chemical Machine program looks like

A "chemical program" has the following three basic parts:

1. Declarations of new molecule emitters and their types.
2. Declarations of reactions and reaction sites containing them.
3. Some emitter calls to emit initial molecules.

Since reactions and molecule emitters are values, they may be passed as arguments to functions, returned by functions, or emitted as payload values on molecules.
For this reason, any part of the application code — including reaction bodies — can define new emitters, new reactions and reactions sites, and emit new molecules.

Reactions, molecules, and reaction sites are immutable.
Once a reaction site is created, it is impossible to add new reactions to it, or to modify or remove existing reactions from it.

## Example: Asynchronous counter

### Non-blocking read access

We will now implement a counter that can be incremented and whose value can be read.
Both the `increment` and the `read` operations are asynchronous (non-blocking).
The read operation is implemented as an _asynchronous continuation_.

```tut
val counter = m[Int]
val increment = m[Unit] // The `increment` operation.
val read = m[Int ⇒ Unit] // Continuation for the `read` operation.

site(
  go { case counter(x) + increment(_) ⇒ counter(x + 1) },
  go { case counter(x) + read(cont) ⇒
    counter(x)   // Emit the `counter` molecule with unchanged value `x`.
    cont(x)      // Invoke continuation.
  } 
)
counter(0)   // Set initial value of `counter` to 0.
increment()  // Shorter syntax: emit a molecule with a `Unit` value.
increment()  // The emitter can be called from any concurrently running code.
read(i ⇒ println(s"counter = $i")) // this too
Thread.sleep(200)

```

A molecule can be consumed only by _one_ instance of a reaction.
For this reason, there are no race conditions when running this program,
even if several copies of the molecules `incr()` and `read()` are emitted from several concurrent processes running in parallel.

### Non-blocking wait until done

We will now implement a counter that is incremented until some condition is met.
At that point, we would like to start another computation that uses the last obtained counter value.

```tut
val counter = m[Int]
val done = m[Int]         // Signal the end of counting.
val next = m[Int ⇒ Unit] // Continuation.
val incr = m[Unit]        // The `increment` operation.

 // The condition we are waiting for, for example:
def are_we_done(x: Int): Boolean = x > 1

site(
  go { case counter(x) + incr(_) ⇒
    val newX = x + 1
    if (are_we_done(newX)) done(newX) else counter(newX) 
  },
  go { case done(x) + next(cont) ⇒
    cont(x) // Invoke continuation on the value `x`.
  }
)
counter(0) // Set the initial value of `counter` to 0.

incr() // Emit a molecule with `Unit` value.
incr() // This can be called from any concurrent process.

next { x ⇒
    // Continue the computation, having obtained `x`.
       println(s"counter = $x")
    // More code...
}
Thread.sleep(200)

```

More code can follow `println()`, but it will be constrained to the scope of the closure under `next()`.

## Blocking emitters

In the previous example, we used a continuation in order to wait until some condition is satisfied.
For convenience, `Chymyst` supports this often-used pattern as a feature the language, via special emitters called **blocking emitters**.
The corresponding molecules are called **blocking molecules**.

Blocking emitters can be understood as molecule emitters that automatically include a built-in continuation function.
A reaction that consume a blocking molecule should call the continuation function, which can be seen as emitting a **reply value**.
A call to emit a blocking molecule will block the calling thread, until a reaction starts, consumes the blocking molecule, and emits a reply value.
After that, the calling thread will receive the reply value, and its execution will continue.

Blocking emitters are declared using the `b[T, R]` syntax, where `T` is the type of the molecule's payload value and `R` is the type of the reply value.

Using this feature, the previous code can be rewritten more concisely:

```tut
val counter = m[Int]
val done = m[Int]       // Signal the end of counting.
val next = b[Unit, Int] // Blocking emitter with an integer reply value.
val incr = m[Unit]      // The `increment` operation.

 // The condition we are waiting for, for example:
def are_we_done(x: Int): Boolean = x > 1

site(
  go { case counter(x) + incr(_) ⇒
    val newX = x + 1
    if (are_we_done(newX)) done(newX) else counter(newX) 
  },
  go { case done(x) + next(_, reply) ⇒
    reply(x) // Emit reply with integer value `x`.
  }
)

counter(0) // Set initial value of `counter` to 0.

incr() + incr() // Convenience syntax; same as `incr(); incr()`.

val x = next()  // This will block until a reply value is sent.
// Continue the computation, having received the reply value as `x`.
println(s"counter = $x")    // More code...
```

More code can follow `println()`, and that code is no longer constrained to the scope of a closure, as before.

### Asynchronous counter: blocking read access

We can use a blocking molecule to implement the functionality of exclusive, blocking access to a counter's current value.

```tut
val counter = m[Int]
val read = b[Unit, Int] // `read` is a blocking emitter.

val incr = m[Unit] // The `increment` operation is non-blocking.

site(
  go { case counter(x) + incr(_) ⇒ counter(x + 1) },
  go { case counter(x) + read(_, reply) ⇒
    counter(x)  // Emit `x` again as the payload value on the `counter` molecule.
    reply(x)    // Emit reply with value `x`.
  } 
)

counter(0) // Set initial value of `counter` to 0.

incr()
incr()         // These emitter calls do not block.
val x = read() // Block until a reply value is sent.
```

## Parallel `map`

We now implement a parallel `map` operation: apply a function to every element of a list,
and produce a list of results.

We will use an asynchronous counter to keep track of progress.
For simplicity, we will aggregate results into the final list in the order they are computed.
The molecule called `done()` will be emitted when the entire list is processed.
Also, a blocking emitter `waitDone` is used to wait for the completion of the job. 

```tut
val start = m[Int] // Molecule that will carry each list element.

def f(x: Int): Int = x * x // Some computation, whatever.

val total = 10 // Need to know the total number of elements in the list.

val counter = m[Int]
val incr = m[Unit]

val result = m[List[Int]]

val done = m[Unit] // A molecule for signalling the end of computation.
val waitDone = b[Unit, List[Int]] // A blocking molecule for convenience. Its reply value is List[Int].

site(
  go { case start(i) + result(xs) ⇒
    val newXs = f(i) :: xs // Compute i-th element concurrently and append.
    result(newXs)
    incr()
  },
  go { case incr(_) + counter(n) ⇒
    val newN = n + 1
    if (newN == total) done() else counter(newN)
  },
  go { case done(_) + waitDone(_, reply) + result(xs) ⇒ reply(xs) }
)
// Emit initial values.
(1 to total).foreach(i ⇒ start(i))
counter(0)
result(Nil)

val result = waitDone() // Block until done, get result.

```

### Exercise

The code of "parallel map" actually does not compute the function `f(i)` in parallel!
This is so because at most one copy of the molecule `result()` is present at any time, so
at most one instance of the reaction `start() + result() ⇒ ...` could possibly run at once.

Modify the code so that `f(i)` is computed in parallel.

Hint: Introduce a new molecule that will react with `result()` instead of `start()`, and introduce
a new reaction that will produce the new molecule out of `start()` and will perform the computation of `f()`.
