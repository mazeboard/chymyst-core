package io.chymyst.benchmark

import io.chymyst.jc._
import io.chymyst.test.LogSpec

import scala.annotation.tailrec
import scala.concurrent.duration._

class MapReduceSpec extends LogSpec {

  def elapsed(initTime: Long): Long = System.currentTimeMillis() - initTime

  behavior of "map-reduce-like reactions"

  it should "perform a map/reduce-like computation" in {
    val count = 10000

    val initTime = System.currentTimeMillis()

    val res = m[List[Int]]
    val r = m[Int]
    val d = m[Int]
    val get = b[Unit, List[Int]]

    val tp = FixedPool(4)
    site(tp)(
      go { case d(n) => r(n * 2) },
      go { case res(list) + r(s) => res(s :: list) },
      go { case get(_, reply) + res(list) if list.size == count => reply(list) } // ignore warning: "non-variable type argument Int"
    )

    (1 to count).foreach(d(_))
    val expectedResult = (1 to count).map(_ * 2)
    res(Nil)

    get().toSet shouldEqual expectedResult.toSet

    tp.shutdownNow()
    println(s"map/reduce test with n=$count took ${elapsed(initTime)} ms")
  }

  it should "perform map-reduce as in tutorial, object C1" in {
    // declare the "map" and the "reduce" functions
    def f(x: Int): Int = x * x

    def reduceB(acc: Int, x: Int): Int = acc + x

    val initTime = System.currentTimeMillis()

    val arr = 1 to 10000

    // declare molecule types
    val carrier = m[Int]
    val interm = m[Int]
    val accum = m[(Int, Int)]
    val fetch = b[Unit, Int]

    val tp = FixedPool(8)

    // declare the reaction for "map"
    site(tp)(
      go { case carrier(x) => val res = f(x); interm(res) }
    )

    // reactions for "reduce" must be together since they share "accum"
    site(tp)(
      go { case accum((n, b)) + interm(res) =>
        accum((n + 1, reduceB(b, res)))
      },
      go { case accum((n, b)) + fetch(_, reply) if n == arr.size => reply(b) }
    )
    // emit molecules
    accum((0, 0))
    arr.foreach(i => carrier(i))
    val result = fetch()
    result shouldEqual arr.map(f).reduce(reduceB) // 338350
    println(s"map-reduce as in tutorial object C1 with arr.size=${arr.size}: took ${elapsed(initTime)} ms")
    tp.shutdownNow()
  }

  it should "perform map-reduce as in tutorial, object C2" in {
    // declare the "map" and the "reduce" functions
    def f(x: Int): Int = x * x

    def reduceB(acc: Int, x: Int): Int = acc + x

    val initTime = System.currentTimeMillis()

    val arr = 1 to 3000

    // declare molecule types
    val carrier = m[Int]
    val interm = m[(Int, Int)]
    val fetch = b[Unit, Int]

    val tp = FixedPool(8)
    val tp2 = FixedPool(1)

    // reactions for "reduce" must be together since they share "interm"
    site(tp)(
      go { case interm((n1, x1)) + interm((n2, x2)) ⇒
        interm((n1 + n2, reduceB(x1, x2)))
      },
      go { case interm((n, x)) + fetch(_, reply) if n == arr.size ⇒ reply(x) }
    )
    // declare the reaction for "map"
    site(tp2)(
      go { case carrier(x) => val res = f(x); interm((1, res)) }
    )
    // emit molecules
    arr.foreach(i => carrier(i))
    val result = fetch()
    result shouldEqual arr.map(f).reduce(reduceB) // 338350
    println(s"map-reduce as in tutorial object C2 with arr.size=${arr.size}: took ${elapsed(initTime)} ms")
    tp.shutdownNow()
    tp2.shutdownNow()
  }

  it should "perform map-reduce as in object C2 but without reaction guard" in {
    // declare the "map" and the "reduce" functions
    def f(x: Int): Int = x * x

    def reduceB(acc: Int, x: Int): Int = acc + x

    val initTime = System.currentTimeMillis()

    val arr = 1 to 30000

    // declare molecule types
    val carrier = m[Int]
    val interm = m[(Int, Int)]
    val fetch = b[Unit, Int]
    val done = m[Int]

    val tp = FixedPool(8)
    val tp2 = FixedPool(1)

    // reactions for "reduce" must be together since they share "interm"
    site(tp)(
      go { case interm((n1, x1)) + interm((n2, x2)) ⇒
        val n = n1 + n2
        val x = reduceB(x1, x2)
        if (n == arr.size) done(x)
        else interm((n, x))
      }
    )
    // declare the reaction for "map"
    site(tp2)(
      go { case done(x) + fetch(_, reply) ⇒ reply(x) },
      go { case carrier(x) => val res = f(x); interm((1, res)) }
    )
    // emit molecules
    arr.foreach(i => carrier(i))
    val result = fetch()
    result shouldEqual arr.map(f).reduce(reduceB) // 338350
    println(s"map-reduce as in tutorial object C2 but without guard, with arr.size=${arr.size}: took ${elapsed(initTime)} ms")
    tp.shutdownNow()
    tp2.shutdownNow()
  }

  it should "compute the sum of numbers on molecules using nonlinear input pattern" in {
    val c = m[(Int, Int)]
    val done = m[Int]
    val f = b[Unit, Int]

    val count = 10000

    val tp = FixedPool(cpuCores + 1)
    val initTime = System.currentTimeMillis()

    site(tp)(
      go { case f(_, r) + done(x) => r(x) },
      go { case c((n, x)) + c((m, y)) =>
        val p = n + m
        val z = x + y
        if (p == count)
          done(z)
        else
          c((n + m, z))
      }
    )

    (1 to count).foreach(i => c((1, i * i)))
    f() shouldEqual (1 to count).map(i => i * i).sum

    tp.shutdownNow()
    println(s"sum of $count numbers with nonlinear input patterns took ${elapsed(initTime)} ms")
  }

  it should "compute the sum of numbers on molecules using nonlinear input pattern and cross-molecule conditionals" in {
    val c = m[(Int, Int)]
    val done = m[Int]
    val f = b[Unit, Int]

    val count = 10000

    val tp = FixedPool(cpuCores + 1)
    val tp2 = FixedPool(1)
    val initTime = System.currentTimeMillis()

    site(tp)(
      go { case f(_, r) + done(x) => r(x) },
      go { case c((n, x)) + c((m, y)) if x <= y =>
        val p = n + m
        val z = x + y
        if (p == count)
          done(z)
        else
          c((n + m, z))
      }
    )

    (1 to count).foreach(i => c((1, i * i)))
    f() shouldEqual (1 to count).map(i => i * i).sum

    tp.shutdownNow()
    tp2.shutdownNow()
    println(s"sum of $count numbers with nonlinear input patterns and cross-molecule conditionals took ${elapsed(initTime)} ms")
  }

  it should "compute the sum of numbers on molecules using nonlinear input pattern and branching emitters" in {
    val a = m[Int]
    val c = m[(Int, Int)]
    val done = m[Int]
    val f = b[Unit, Int]

    val count = 100000

    val tp = FixedPool(cpuCores + 1)
    val tp2 = FixedPool(1)
    val tp3 = FixedPool(1)
    val initTime = System.currentTimeMillis()

    site(tp2)(
      go { case f(_, r) + done(x) => r(x) }
    )

    site(tp3)(
      go { case c((n, x)) + c((m, y)) =>
        val p = n + m
        val z = x + y
        if (p >= count)
          done(z)
        else
          c((n + m, z))
      }
    )

    site(tp)(
      go {
        case a(x) if x <= count ⇒
          c((1, x * x))
          // When these IF conditions are restored, performance improves slightly.
          //          if (x * 2 <= count)
          a(x * 2)
          //          if (x * 2 + 1 <= count)
          a(x * 2 + 1)
      }
    )

    a(1)
    f() shouldEqual (1 to count).map(i => i * i).sum

    Seq(tp, tp2, tp3).foreach(_.shutdownNow())
    println(s"sum of $count numbers with nonlinear input patterns, branching emitters, took ${elapsed(initTime)} ms")
  }

  it should "correctly process concurrent counters" in {
    // Same logic as Benchmark 1 but designed to catch race conditions more quickly.
    def make_counter_1(done: M[Unit], counters: Int, init: Int, reactionPool: Pool): B[Unit, Unit] = {
      val c = m[Int]
      val d = b[Unit, Unit]

      site(reactionPool)(
        go { case c(0) ⇒ done() },
        go { case c(x) + d(_, r) if x > 0 ⇒ c(x - 1); r() }
      )
      (1 to counters).foreach(_ ⇒ c(init))
      // We return just one molecule.
      d
    }

    val initTime = System.currentTimeMillis()
    var failures = 0
    val n = 1000
    val numberOfCounters = 10
    val count = 2

    (1 to n).foreach { _ ⇒
      val tp = FixedPool(numberOfCounters)

      val done = m[Unit]
      val all_done = m[Int]
      val f = b[Long, Long]

      site(tp)(
        go { case all_done(0) + f(tInit, r) ⇒ r(elapsed(tInit)) },
        go { case all_done(x) + done(_) if x > 0 ⇒ all_done(x - 1) }
      )
      val initialTime = System.currentTimeMillis()
      all_done(numberOfCounters)
      val d = make_counter_1(done, numberOfCounters, count, tp)
      // emit a blocking molecule `d` many times
      (1 to (count * numberOfCounters)).foreach(_ ⇒ d())
      val result = f.timeout(initialTime)(1.second)
      if (result.isEmpty) {
        failures += 1
      }
      tp.shutdownNow()
    }
    println(s"concurrent counters correctness check: $numberOfCounters counters, count = $count, $n numbers, took ${elapsed(initTime)} ms")

    (if (failures > 0) s"Detected $failures failures out of $n tries" else "OK") shouldEqual "OK"
  }

  behavior of "ordered map/reduce"

  val countHierarchical = 10000

  /** A simple binary operation on integers that is associative but not commutative.
    * op(x,y) = x + y if x is even, and x - y if x is odd.
    * See: F. J. Budden. A Non-Commutative, Associative Operation on the Reals. The Mathematical Gazette, Vol. 54, No. 390 (Dec., 1970), pp. 368-372
    * http://www.jstor.org/stable/3613855
    *
    * @param x First integer.
    * @param y Second integer.
    * @return Integer result.
    */
  def assocNonCommutOperation(x: Int, y: Int): Int = {
    val s = 1 - math.abs(x % 2) * 2 // s = 1 if x is even, s = -1 if x is odd; math.abs is needed to fix the bug where (-1) % 2 == -1
    //    Thread.sleep(1) // imitate longer computations
    x + s * y
  }

  it should "compute associative, noncommutative operation" in {
    assocNonCommutOperation(1, 13) shouldEqual -12
    assocNonCommutOperation(13, 1) shouldEqual 12
    assocNonCommutOperation(2, 13) shouldEqual 15
    assocNonCommutOperation(13, 2) shouldEqual 11
    assocNonCommutOperation(23, assocNonCommutOperation(57, 98)) shouldEqual assocNonCommutOperation(assocNonCommutOperation(23, 57), 98)
  }

  def orderedMapReduce(count: Int): Unit = {
    // c((l, r, x)) represents the left-closed, right-open interval (l, r) over which we already performed the reduce operation, and the result value x.
    val c = m[(Int, Int, Int)]
    val done = m[Int]
    val f = b[Unit, Int]

    val tp = FixedPool(cpuCores)
    val tp2 = FixedPool(1)
    val initTime = System.currentTimeMillis()

    site(tp2)(
      go { case f(_, r) + done(x) => r(x) }
    )

    site(tp)(
      go { case c((l1, r1, x)) + c((l2, r2, y)) if r2 == l1 || l2 == r1 =>
        val l3 = math.min(l1, l2)
        val r3 = math.max(r1, r2)
        val z = if (l2 == r1) assocNonCommutOperation(x, y) else assocNonCommutOperation(y, x)
        if (r3 - l3 == count)
          done(z)
        else
          c((l3, r3, z))
      }
    )

    (1 to count).foreach(i => c((i, i + 1, i * i)))
    f() shouldEqual (1 to count).map(i => i * i).reduce(assocNonCommutOperation)
    println(s"associative but non-commutative reduceB() on $count numbers with nonlinear input patterns took ${elapsed(initTime)} ms")
    tp.shutdownNow()
    tp2.shutdownNow()
  }

  it should "perform ordered map-reduce using conditional reactions" in {
    orderedMapReduce(count = countHierarchical)
  }

  it should "perform ordered map-reduce using unique reactions (very slow due to thousands of reactions defined)" in {
    // c((l, r, x)) represents the left-closed, right-open interval (l, r) over which we already performed the reduce operation, and the result value x.
    val done = m[Int]
    val f = b[Unit, Int]

    val count = 30

    val tp = FixedPool(cpuCores)
    val initTime = System.currentTimeMillis()

    site(tp)(
      go { case f(_, r) + done(x) => r(x) }
    )

    // Define all molecule emitters as a 2-indexed map
    val emitters: Map[(Int, Int), M[Int]] =
      (0 until count).flatMap(i ⇒ (i + 1 to count).map(j ⇒ (i, j) → new M[Int](s"c[$i,$j]")))(scala.collection.breakOut)

    val lastMol = emitters((0, count))

    val reactions = (0 until count).flatMap(i ⇒ (i + 1 to count).flatMap(j ⇒ (j + 1 to count).map { k ⇒
      val mol1 = emitters((i, j))
      val mol2 = emitters((j, k))
      val mol3 = emitters((i, k))
      go { case mol1(x) + mol2(y) ⇒ mol3(assocNonCommutOperation(x, y)) }
    })) :+ go { case lastMol(x) ⇒ done(x) }

    println(s"created emitters and reactions: at ${elapsed(initTime)} ms")

    site(tp)(reactions: _*)

    println(s"defined reactions: at ${elapsed(initTime)} ms")

    (1 to count).foreach(i ⇒ emitters((i - 1, i))(i * i))
    f() shouldEqual (1 to count).map(i => i * i).reduce(assocNonCommutOperation)
    println(s"associative but non-commutative reduceB() on $count numbers with ${emitters.size} unique molecules, ${reactions.size} unique reactions took ${elapsed(initTime)} ms")
  }

  behavior of "hierarchical ordered map/reduce"

  def hierarchicalMapReduce[T](array: Array[T], result: M[T], reduceB: (T, T) ⇒ T, tp: Pool): Unit = {
    val reduceAll = m[(Array[T], M[T])]
    site(tp)(
      go { case reduceAll((arr, res)) ⇒
        if (arr.length == 1) res(arr(0))
        else {
          val (arr0, arr1) = arr.splitAt(arr.length / 2)
          val a0 = m[T]
          val a1 = m[T]
          site(tp)(go { case a0(x) + a1(y) ⇒ res(reduceB(x, y)) })
          reduceAll((arr0, a0)) + reduceAll((arr1, a1))
        }
      }
    )
    reduceAll((array, result))
    // The result() molecule will be emitted with the final result.
  }

  def hierarchicalMapReduce2[T](array: Array[T], result: M[T], reduceB: (T, T) ⇒ T, tp: Pool): Unit = {
    val reduceAll = m[(Int, Int, M[T])]
    site(tp)(
      go { case reduceAll((p, q, res)) ⇒
        if (q - p == 1) res(array(p))
        else if (q - p == 2) res(reduceB(array(p), array(p + 1)))
        else {
          val middle = (p + q) / 2
          val a0 = m[T]
          val a1 = m[T]
          site(tp)(go { case a0(x) + a1(y) ⇒ res(reduceB(x, y)) })
          reduceAll((p, middle, a0)) + reduceAll((middle, q, a1))
        }
      }
    )
    reduceAll((0, array.length, result))
    // The result() molecule will be emitted with the final result.
  }

  def hierarchicalMapReduce3[T](array: Array[T], result: M[T], reduceB: (T, T) ⇒ T, tp: Pool): Unit = {
    val reduceAll = m[(Int, Int, M[T])]
    site(tp)(
      go { case reduceAll((p, q, res)) ⇒
        if (q - p == 1) res(array(p))
        else if (q - p == 2) res(reduceB(array(p), array(p + 1)))
        else {
          val middle1 = (2 * p + q) / 3
          val middle2 = (p + 2 * q) / 3
          val a0 = m[T]
          val a1 = m[T]
          val a2 = m[T]
          val a01 = m[T]
          val a12 = m[T]
          site(tp)(
            go { case a0(x) + a1(y) ⇒ a01(reduceB(x, y)) },
            go { case a0(x) + a12(y) ⇒ res(reduceB(x, y)) },
            go { case a1(x) + a2(y) ⇒ a12(reduceB(x, y)) },
            go { case a01(x) + a2(y) ⇒ res(reduceB(x, y)) }
          )
          reduceAll((p, middle1, a0)) + reduceAll((middle1, middle2, a1)) + reduceAll((middle2, q, a2))
        }
      }
    )
    reduceAll((0, array.length, result))
    // The result() molecule will be emitted with the final result.
  }

  @tailrec
  final def makeReactionPlanLogN[T](
    nmax: Int,
    ns: IndexedSeq[Int],
    results: IndexedSeq[(M[T], Int, Int)],
    reduceB: (T, T) ⇒ T,
    rs: IndexedSeq[IndexedSeq[Reaction]] = IndexedSeq()
  ): (IndexedSeq[IndexedSeq[Reaction]], IndexedSeq[(M[T], Int, Int)]) = {
    if (nmax <= 2) {
      // homogeneous case
      (rs, results)
    } else if (nmax == 3) {
      // inhomogeneous case: need to add molecules and reactions for the 3-groups
      val above3 = ns.zipWithIndex.filter { case (n, _) ⇒ n == 3 }.map(_._2)
      val sums = ns.scanLeft(0)(_ + _).take(ns.length)

      val newMols: Map[Int, IndexedSeq[(M[T], Int, Int)]] = above3.map { i ⇒
        i → IndexedSeq(
          (new M[T](s"a$nmax-$i"), sums(i), sums(i) + 1),
          (new M[T](s"a$nmax-$i-"), sums(i) + 1, sums(i) + 3)
        )
      }(scala.collection.breakOut)
      val newReactions = above3.map { i ⇒
        val a = results(i)._1
        val as = newMols(i)
        val a0 = as(0)._1
        val a1 = as(1)._1
        go { case a0(x) + a1(y) ⇒ a(reduceB(x, y)) }
      }
      val previousMols = results.filter { case (_, p, q) ⇒ q - p < 3 } // previousMols must be replaced by newMols
      (rs :+ newReactions, newMols.values.toIndexedSeq.flatten ++ previousMols)
    } else {
      // recursive case
      val nmaxNew = (nmax + 1) / 2
      val nsNew = ns.flatMap(n ⇒ IndexedSeq(n / 2, (n + 1) / 2))
      val sums = nsNew.scanLeft(0)(_ + _).take(nsNew.length)
      val resultsNew = nsNew.indices.map(i ⇒ (new M[T](s"a$nmax-$i"), sums(i), sums(i) + nsNew(i)))
      val newReactions: IndexedSeq[Reaction] = {
        results.indices.map { i ⇒
          val a = results(i)._1
          val a0 = resultsNew(i * 2)._1
          val a1 = resultsNew(i * 2 + 1)._1
          go { case a0(x) + a1(y) ⇒ a(reduceB(x, y)) }
        }
      }
      makeReactionPlanLogN(nmaxNew, nsNew, resultsNew, reduceB, rs :+ newReactions)
    }
  }

  it should "use algorithm with binary split" in {
    val tp = FixedPool(cpuCores + 2)
    val tp1 = FixedPool(1)

    val result = m[Int]
    val f = b[Unit, Int]

    site(tp1)(
      go { case result(x) + f(_, r) => r(x) }
    )
    val data = (1 to countHierarchical).map(i ⇒ i * i)

    val initTime = System.currentTimeMillis()
    hierarchicalMapReduce(data.toArray, result, assocNonCommutOperation, tp)
    val res = f()
    println(s"associative but non-commutative hierarchical reduceB() on $countHierarchical numbers took ${elapsed(initTime)} ms")
    res shouldEqual data.reduce(assocNonCommutOperation)

    tp.shutdownNow()
    tp1.shutdownNow()
  }

  it should "use algorithm with optimizations in binary split" in {
    val tp = FixedPool(cpuCores + 2)
    val tp1 = FixedPool(1)

    val result = m[Int]
    val f = b[Unit, Int]

    site(tp1)(
      go { case result(x) + f(_, r) => r(x) }
    )
    val data = (1 to countHierarchical).map(i ⇒ i * i)

    val initTime = System.currentTimeMillis()
    hierarchicalMapReduce2(data.toArray, result, assocNonCommutOperation, tp)
    val res = f()
    println(s"associative but non-commutative hierarchical reduceB() on $countHierarchical numbers with optimization took ${elapsed(initTime)} ms")
    res shouldEqual data.reduce(assocNonCommutOperation)

    tp.shutdownNow()
    tp1.shutdownNow()
  }

  it should "use algorithm with ternary split" in {
    val tp = FixedPool(cpuCores + 2)
    val tp1 = FixedPool(1)

    val result = m[Int]
    val f = b[Unit, Int]

    site(tp1)(
      go { case result(x) + f(_, r) => r(x) }
    )
    val data = (1 to countHierarchical).map(i ⇒ i * i)

    val initTime = System.currentTimeMillis()
    hierarchicalMapReduce3(data.toArray, result, assocNonCommutOperation, tp)
    val res = f()
    println(s"associative but non-commutative hierarchical reduceB() on $countHierarchical numbers with ternary split took ${elapsed(initTime)} ms")
    res shouldEqual data.reduce(assocNonCommutOperation)

    tp.shutdownNow()
    tp1.shutdownNow()
  }

  it should "use algorithm with log(n) reaction sites" in {
    val tp = FixedPool(cpuCores + 2)
    val tp1 = FixedPool(1)

    val count = countHierarchical

    val result = m[Int]
    val f = b[Unit, Int]

    site(tp1)(
      go { case result(x) + f(_, r) => r(x) }
    )
    val data = (1 to count).map(i ⇒ i * i)

    val initTime = System.currentTimeMillis()
    val len = data.length
    val (reactions, emitterList) = makeReactionPlanLogN(len, IndexedSeq(len), IndexedSeq((result, 0, len)), assocNonCommutOperation)

    println(s"computed reaction plan at ${elapsed(initTime)}")

    // Declare reaction sites.
    reactions.foreach(rs ⇒ site(tp)(rs: _*))
    println(s"declared ${reactions.length} reaction sites, total ${reactions.flatten.length} reactions, at ${elapsed(initTime)}")
    // Emit initial molecules. The distance between p and q can be at most 2.
    emitterList.foreach { case (mol, p, q) ⇒
      if (q - p == 1) mol(data(p)) else mol(assocNonCommutOperation(data(p), data(p + 1)))
    }
    println(s"emitted initial molecules at ${elapsed(initTime)}")

    // The result() molecule will be emitted with the final result.
    val res = f()
    println(s"associative but non-commutative hierarchical reduceB() on n=$count numbers with log(n) reaction sites took ${elapsed(initTime)} ms")
    res shouldEqual data.reduce(assocNonCommutOperation)

    tp.shutdownNow()
    tp1.shutdownNow()
  }
}
