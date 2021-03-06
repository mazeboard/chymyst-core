package io.chymyst.test

import java.util.concurrent.ConcurrentLinkedQueue

import io.chymyst.jc._
import io.chymyst.test.Common._
import org.scalatest.BeforeAndAfterEach

import scala.collection.JavaConverters.asScalaIteratorConverter
import scala.concurrent.duration._
import scala.language.postfixOps

class Patterns01Spec extends LogSpec with BeforeAndAfterEach {

  var tp: Pool = _

  override def beforeEach(): Unit = {
    tp = BlockingPool(8)
  }

  override def afterEach(): Unit = {
    tp.shutdownNow()
  }

  behavior of "Chymyst"

  it should "implement exchanger (rendezvous with data exchange) with 2 processes and 1 barrier() molecule" in {
    val barrier = b[Int, Int]

    val begin1 = m[Unit]
    val begin2 = m[Unit]

    val end1 = m[Int]
    val end2 = m[Int]
    val done = b[Unit, (Int, Int)]

    site(tp)(
      go { case begin1(_) =>
        val x1 = 123
        // some computation
        val y1 = barrier(x1)
        // receive value from Process 2
        val z = y1 * y1 // further computation
        end1(z)
      }
    )

    site(tp)(
      go { case begin2(_) =>
        val x2 = 456
        // some computation
        val y2 = barrier(x2)
        // receive value from Process 1
        val z = y2 * y2 // further computation
        end2(z)
      }
    )

    site(tp)(
      // The values are exchanged in this reaction.
      go { case barrier(x1, r1) + barrier(x2, r2) => r1(x2); r2(x1) }
    )

    site(tp)(
      go { case end1(x1) + end2(x2) + done(_, r) => r((x1, x2)) }
    )

    begin1() + begin2() // emit both molecules to enable starting the two reactions

    val result = done()
    result shouldEqual ((456 * 456, 123 * 123))
  }

  it should "implement barrier (rendezvous without data exchange) with 2 processes and 2 barrier() molecules" in {
    val barrier1 = b[Unit, Unit]
    val barrier2 = b[Unit, Unit]

    val begin1 = m[Unit]
    val begin2 = m[Unit]

    val end1 = m[Unit]
    val end2 = m[Unit]
    val done = b[Unit, Unit]

    val logFile = new ConcurrentLinkedQueue[String]

    def f1() = logFile.add("f1")

    def f2() = logFile.add("f2")

    def g1() = logFile.add("g1")

    def g2() = logFile.add("g2")

    site(tp)(
      go { case begin1(_) => f1(); barrier1(); g1(); end1() },
      go { case begin2(_) => f2(); barrier2(); g2(); end2() },
      go { case barrier1(_, r1) + barrier2(_, r2) => r1(); r2() },
      go { case end1(_) + end2(_) + done(_, r) => r() }
    )

    begin1() + begin2()
    done()
    val result: Seq[String] = logFile.iterator().asScala.toSeq
    // Now, there must be f1 and f2 (in any order) before g1 and g2 (also in any order).
    // We use `Set` to verify this.
    result.size shouldEqual 4
    Set(result(0), result(1)) shouldEqual Set("f1", "f2")
    Set(result(2), result(3)) shouldEqual Set("g1", "g2")
  }

  it should "implement barrier (rendezvous without data exchange) with 4 processes and 1 barrier() molecule" in {
    val barrier = b[Unit, Unit]

    val begin1 = m[Unit]
    val begin2 = m[Unit]
    val begin3 = m[Unit]
    val begin4 = m[Unit]

    val end1 = m[Unit]
    val end2 = m[Unit]
    val end3 = m[Unit]
    val end4 = m[Unit]
    val done = b[Unit, Unit]

    val logFile = new ConcurrentLinkedQueue[String]

    def f1() = logFile.add("f1")

    def f2() = logFile.add("f2")

    def f3() = logFile.add("f4")

    def f4() = logFile.add("f3")

    def g1() = logFile.add("g1")

    def g2() = logFile.add("g2")

    def g3() = logFile.add("g4")

    def g4() = logFile.add("g3")

    site(tp)(
      go { case begin1(_) => f1(); barrier(); g1(); end1() },
      go { case begin2(_) => f2(); barrier(); g2(); end2() },
      go { case begin3(_) => f3(); barrier(); g3(); end3() },
      go { case begin4(_) => f4(); barrier(); g4(); end4() },
      go { case barrier(_, r1) + barrier(_, r2) + barrier(_, r3) + barrier(_, r4) => r1(); r2(); r3(); r4() },
      go { case end1(_) + end2(_) + end3(_) + end4(_) + done(_, r) => r() }
    )

    begin1() + begin2() + begin3() + begin4()
    done()
    val result: Seq[String] = logFile.iterator().asScala.toSeq
    // Now, there must be f1 and f2 (in any order) before g1 and g2 (also in any order).
    // We use `Set` to verify this.
    result.size shouldEqual 8
    (0 to 3).map(result).toSet shouldEqual Set("f1", "f2", "f3", "f4")
    (4 to 7).map(result).toSet shouldEqual Set("g1", "g2", "g3", "g4")
  }

  it should "implement barrier (rendezvous without data exchange) with n processes" in {

    val n = 100 // The number of rendezvous participants needs to be known in advance, or else we don't know how long still to wait for rendezvous.

    // There will be 2*n blocked threads; the test will fail with FixedPool(2*n-1).
    withPool(FixedPool(2 * n)) { pool =>

      val barrier = b[Unit, Unit]
      val counterInit = m[Unit]
      val counter = b[Int, Unit]
      val endCounter = m[Int]
      val begin = m[(() => Unit, () => Unit)]
      val end = m[Unit]
      val done = b[Unit, Unit]

      val logFile = new ConcurrentLinkedQueue[String]

      def f(n: Int): () ⇒ Unit = { () ⇒
        logFile.add(s"f$n")
        ()
      }

      def g(n: Int): () ⇒ Unit = { () ⇒
        logFile.add(s"g$n")
        ()
      }

      site(pool)(
        go { case begin((f, g)) => f(); barrier(); g(); end() }, // this reaction will be run n times because we emit n molecules `begin` with various `f` and `g`
        go { case barrier(_, replyB) + counterInit(_) => // this reaction will consume the very first barrier molecule emitted
          counter(1) // one reaction has reached the rendezvous point
          replyB()
        },
        go { case barrier(_, replyB) + counter(k, replyC) => // the `counter` molecule holds the number (k) of the reactions that have reached the rendezvous before this reaction started.
          if (k + 1 < n) counter(k + 1); else println(s"rendezvous passed by $n reactions")
          replyC() // `replyC()` must be here. Doing `replyC()` before emitting `counter(k+1)` would have unblocked some reactions and allowed them to proceed beyond the rendezvous point without waiting for all others.
          replyB()
        },
        go { case end(_) + endCounter(k) => endCounter(k - 1) },
        go { case endCounter(0) + done(_, r) => r() }
      )

      (1 to n).foreach(i => begin((f(i), g(i))))
      counterInit()
      endCounter(n)
      done.timeout()(5000 millis) shouldEqual Some(())

      val result: Seq[String] = logFile.iterator().asScala.toSeq
      result.size shouldEqual 2 * n
      // Now, there must be f_1, ..., f_n (in any order) before g_1, ..., g_n (also in any order).
      // We use sets to verify this.

      val setF = (0 until n).map(result.apply).toSet
      val setG = (n until 2 * n).map(result.apply).toSet

      val expectedSetF = (1 to n).map(i => s"f$i").toSet
      val expectedSetG = (1 to n).map(i => s"g$i").toSet

      setF diff expectedSetF shouldEqual Set()
      setG diff expectedSetG shouldEqual Set()

      expectedSetF diff setF shouldEqual Set()
      expectedSetG diff setG shouldEqual Set()
    }.get
  }

  it should "implement dance pairing with simple reaction" in {
    val total = 10000

    val man = m[Int]
    val woman = m[Int]
    val finished = m[Unit]
    val counter = new ConcurrentLinkedQueue[Int]()

    def beginDancing(x: Int): Boolean = {
      counter.add(x)
      counter.size == total
    }

    val done = b[Unit, Unit]

    val tp = FixedPool(1)
    val tp1 = FixedPool(1)
    site(tp)(
      go { case finished(_) + done(_, r) ⇒ r() }
    )
    site(tp1)(
      go { case man(xy) + woman(xx) ⇒ if (beginDancing(Math.min(xx, xy))) finished() }
    )
    checkExpectedPipelined(Seq(man, woman).map(_ → true).toMap) shouldEqual ""

    (0 until total / 2).foreach(x => man(x))
    (0 until total / 2).foreach(x => man(x + total / 2) + woman(x))
    (0 until total / 2).foreach(x => woman(x + total / 2))

    val initTime = System.currentTimeMillis()
    done()
    tp.shutdownNow()
    tp1.shutdownNow()
    val ordering = counter.iterator().asScala.toIndexedSeq
    val outOfOrder = ordering.zip(ordering.drop(1)).filterNot { case (x, y) => x + 1 == y }.map(_._1)
    println(s"Dance pairing for $total pairs without queue labels took ${System.currentTimeMillis() - initTime} ms, yields ${outOfOrder.length} out-of-order instances")
    outOfOrder shouldEqual Vector() // Dancing queue order is observed.
  }

  it should "implement dance pairing without queue labels" in {
    val man = m[Int]
    val manL = m[Int]
    val queueMen = m[Int]
    val woman = m[Int]
    val womanL = m[Int]
    val queueWomen = m[Int]
    val beginDancing = m[Int]

    val danceCounter = m[Vector[Int]]
    val done = b[Unit, Vector[Int]]

    val total = 10000
    val tp = BlockingPool(4) // Use BlockingPool because using a FixedPool gives a deadlock.
    site(tp)(
      go { case danceCounter(x) + done(_, r) if x.size == total => r(x); danceCounter(x) }, // ignore warning about "non-variable type argument Int"
      go { case beginDancing(xy) + danceCounter(x) => danceCounter(x :+ xy) },
      go { case _ => danceCounter(Vector()) }
    )

    site(tp)(
      go { case man(_) + queueMen(n) => queueMen(n + 1) + manL(n) },
      go { case woman(_) + queueWomen(n) => queueWomen(n + 1) + womanL(n) },
      go { case manL(xy) + womanL(xx) => beginDancing(Math.min(xx, xy)) },
      go { case _ => queueMen(0) + queueWomen(0) }
    )
    checkExpectedPipelined(Seq(man, woman, queueMen, queueWomen, manL, womanL).map(_ → true).toMap) shouldEqual ""

    (0 until total / 2).foreach(x => man(x))
    danceCounter.volatileValue shouldEqual Nil
    (0 until total / 2).foreach(x => man(x) + woman(x))
    (0 until total / 2).foreach(x => woman(x))

    val initTime = System.currentTimeMillis()
    val ordering = done()
    tp.shutdownNow()
    val outOfOrder = ordering.zip(ordering.drop(1)).filterNot { case (x, y) => x + 1 == y }.map(_._1)
    println(s"Dance pairing for $total pairs without queue labels took ${System.currentTimeMillis() - initTime} ms, yields ${outOfOrder.length} out-of-order instances")
    outOfOrder should not equal Vector() // Dancing queue order cannot be observed.
  }

  it should "implement dance pairing without queue labels with 1-thread pipelining" in {
    val man = m[Unit]
    val manL = m[Int]
    val queueMen = m[Int]
    val woman = m[Unit]
    val womanL = m[Int]
    val queueWomen = m[Int]
    val beginDancing = m[Int]

    val danceCounter = m[Vector[Int]]
    val done = b[Unit, Vector[Int]]

    val total = 50000

    val tp1a = FixedPool(1)
    val tp1b = FixedPool(1)
    val tp1c = FixedPool(1)
    val tp1d = FixedPool(1)

    site(tp)(
      go { case danceCounter(x) + done(_, r) if x.size == total => r(x); danceCounter(x) }, // ignore warning about "non-variable type argument Int"
      go { case beginDancing(xy) + danceCounter(x) => danceCounter(x :+ xy) } onThreads tp1d,
      go { case _ => danceCounter(Vector()) }
    )
    site(tp)(
      go { case man(_) + queueMen(n) => queueMen(n + 1) + manL(n) } onThreads tp1a,
      go { case woman(_) + queueWomen(n) => queueWomen(n + 1) + womanL(n) } onThreads tp1b,
      go { case manL(xy) + womanL(xx) => beginDancing(Math.min(xx, xy)) } onThreads tp1c,
      go { case _ => queueMen(0) + queueWomen(0) }
    )
    checkExpectedPipelined(Seq(man, woman, queueMen, queueWomen, manL, womanL).map(_ → true).toMap) shouldEqual ""

    (0 until total / 2).foreach(_ => man())
    danceCounter.volatileValue shouldEqual Nil
    (0 until total / 2).foreach(_ => man() + woman())
    (0 until total / 2).foreach(_ => woman())

    val initTime = System.currentTimeMillis()
    val ordering = done()

    Seq(tp1a, tp1b, tp1c, tp1d).foreach(_.shutdownNow())

    val outOfOrder = ordering.zip(ordering.drop(1)).filterNot { case (x, y) => x + 1 == y }.map(_._1)
    println(s"Dance pairing for $total pairs without queue labels with 1-thread pools took ${System.currentTimeMillis() - initTime} ms, yields ${outOfOrder.length} out-of-order instances")
    outOfOrder shouldEqual Vector() // Dancing queue order is observed.
  }

  it should "implement dance pairing with queue labels" in {
    val man = m[Unit]
    val manL = m[Int]
    val queueMen = m[Int]
    val woman = m[Unit]
    val womanL = m[Int]
    val queueWomen = m[Int]
    val beginDancing = b[Int, Unit]
    val mayBegin = m[Int]

    val danceCounter = m[List[Int]]
    val done = b[Unit, List[Int]]

    val total = 100

    site(tp)(
      go { case danceCounter(x) + done(_, r) if x.size == total => r(x); danceCounter(x) }, // ignore warning about "non-variable type argument Int"
      go { case beginDancing(xy, r) + danceCounter(x) => danceCounter(x :+ xy); r() },
      go { case _ => danceCounter(Nil) }
    )

    site(tp)(
      go { case man(_) + queueMen(n) => queueMen(n + 1) + manL(n) },
      go { case woman(_) + queueWomen(n) => queueWomen(n + 1) + womanL(n) },
      go { case manL(xy) + womanL(xx) + mayBegin(l) if xx == xy && xy == l => beginDancing(l); mayBegin(l + 1) },
      go { case _ => queueMen(0) + queueWomen(0) + mayBegin(0) }
    )

    checkExpectedPipelined(Map(man -> true, woman -> true, queueMen -> true, queueWomen -> true, manL -> false, womanL -> false, mayBegin -> false)) shouldEqual ""

    //    tp.reporter = ConsoleDebugAllReporter

    (0 until total / 2).foreach(_ => man())
    danceCounter.volatileValue shouldEqual Nil
    (0 until total / 2).foreach(_ => man() + woman())
    (0 until total / 2).foreach(_ => woman())

    val initTime = System.currentTimeMillis()
    val ordering = done()
    println(s"Dance pairing for $total pairs with queue labels took ${System.currentTimeMillis() - initTime} ms")
    val outOfOrder = ordering.zip(ordering.drop(1)).filterNot { case (x, y) => x + 1 == y }.map(_._1)
    outOfOrder shouldEqual List()
    ordering shouldEqual (0 until total).toList // Dancing queue order must be observed.
  }

  it should "implement simple pipelining" in {
    val c = m[Int]
    val res = m[List[Int]]
    val done = m[List[Int]]
    val f = b[Unit, List[Int]]

    val total = 50000

    site(tp)(
      go { case c(x) + res(l) ⇒ val newL = x :: l; if (x >= total) done(newL); res(newL) }
      , go { case f(_, r) + done(l) ⇒ r(l) }
      , go { case _ ⇒ res(List[Int]()) }
    )
    checkExpectedPipelined(Map(c -> true, res -> true)) shouldEqual ""
    (1 to total).foreach(c)
    val result = f()
    println(s"pipelined molecule, checking with ${result.length} reactions")
    result.reverse shouldEqual (1 to total).toList // emission order must be preserved
  }

  it should "process out of order for non-pipelined molecule" in {
    val c = m[Int]
    val res = m[List[Int]]
    val done = m[List[Int]]
    val f = b[Unit, List[Int]]

    val total = 50000

    site(tp)(
      // This reaction has a cross-molecule guard that is always `true`, but its presence prevents `c` from being pipelined.
      go { case c(x) + res(l) if x > 0 || l.length > -1 ⇒ val newL = x :: l; if (x >= total) done(newL); res(newL) }
      , go { case f(_, r) + done(l) ⇒ r(l) }
      , go { case _ ⇒ res(List[Int]()) }
    )

    checkExpectedPipelined(Map(c -> false, res -> false)) shouldEqual ""
    (1 to total).foreach(c)
    val result = f()
    println(s"non-pipelined molecule, checking with ${result.length} reactions")
    result.reverse shouldNot equal((1 to total).toList) // emission order will not be preserved
  }

  behavior of "ordered readers/writers"

  it should "run correctly" in {
    sealed trait RequestType
    case object Reader extends RequestType
    case object Writer extends RequestType

    val log = new ConcurrentLinkedQueue[Either[String, RequestType]]()

    def readResource() = {
      log.add(Right(Reader))
      Thread.sleep(30)
    }

    def writeResource() = {
      log.add(Right(Writer))
      Thread.sleep(40)
    }

    val tp = FixedPool(4)

    val request = m[RequestType]

    val readerRequest = m[Unit]
    val readerFinished = m[Unit]
    val writerRequest = m[Unit]
    val writerFinished = m[Unit]

    site(tp)(
      go { case readerRequest(_) ⇒ readResource(); log.add(Left("readerFinished")); readerFinished() },
      go { case writerRequest(_) ⇒ writeResource(); log.add(Left("writerFinished")); writerFinished() }
    )

    val noRequests = m[Unit]
    val haveReaders = m[Int]
    val haveWriters = m[Int]
    val haveReadersPendingWriter = m[Int]
    val haveWritersPendingReader = m[Int]
    val pending = m[RequestType]

    val nReaders = 4
    val nWriters = 2

    val consume = m[Unit]

    site(tp)(
      go { case request(r) + consume(_) ⇒ pending(r) },
      go { case pending(Reader) + noRequests(_) ⇒ log.add(Left(s"haveReaders(1)")); readerRequest() + haveReaders(1) + consume() },
      go { case pending(Reader) + haveReaders(k) if k < nReaders ⇒ log.add(Left(s"haveReaders(${k + 1})")); readerRequest() + haveReaders(k + 1) + consume() },

      go { case pending(Writer) + noRequests(_) ⇒ log.add(Left(s"haveWriters(1)")); writerRequest() + haveWriters(1) + consume() },
      go { case pending(Writer) + haveWriters(k) if k < nWriters ⇒ log.add(Left(s"haveWriters(${k + 1})")); writerRequest() + haveWriters(k + 1) + consume() },

      go { case pending(Writer) + haveReaders(k) ⇒ log.add(Left(s"haveReadersPendingWriter($k)")); haveReadersPendingWriter(k) },
      go { case pending(Reader) + haveWriters(k) ⇒ log.add(Left(s"haveWritersPendingReader($k)")); haveWritersPendingReader(k) },

      go { case readerFinished(_) + haveReaders(k) ⇒
        if (k > 1) {
          log.add(Left(s"haveReaders(${k - 1})"))
          haveReaders(k - 1)
        } else {
          log.add(Left(s"noRequests"))
          noRequests()
        }
      },
      go { case readerFinished(_) + haveReadersPendingWriter(k) ⇒
        if (k > 1) {
          log.add(Left(s"haveReadersPendingWriter(${k - 1})"))
          haveReadersPendingWriter(k - 1)
        } else {
          log.add(Left(s"haveWriters(1)"))
          haveWriters(1)
          writerRequest()
          consume()
        }
      },

      go { case writerFinished(_) + haveWriters(k) ⇒ if (k > 1) {
        log.add(Left(s"haveWriters(${k - 1})"))
        haveWriters(k - 1)
      } else {
        log.add(Left(s"noRequests"))
        noRequests()
      }
      },
      go { case writerFinished(_) + haveWritersPendingReader(k) ⇒
        if (k > 1) {
          log.add(Left(s"haveWritersPendingReader(${k - 1})"))
          haveWritersPendingReader(k - 1)
        } else {
          log.add(Left(s"haveReaders(1)"))
          haveReaders(1)
          readerRequest()
          consume()
        }
      }
    )

    consume() + noRequests()

    (0 to 100).map(_ ⇒ if (scala.util.Random.nextInt(2) == 0) Reader else Writer).foreach(request)

    Thread.sleep(5000)

    tp.shutdownNow()

    val trace = log.toArray.toList.map(_.asInstanceOf[Either[String, RequestType]]).scanLeft((0, 0)) {
      case ((r, w), c) ⇒ c match {
        case Right(Writer) ⇒ (r, w + 1)
        case Right(Reader) ⇒ (r + 1, w)
        case Left("readerFinished") ⇒ (r - 1, w)
        case Left("writerFinished") ⇒ (r, w - 1)
        case _ ⇒ (r, w)
      }
    }

    withClue(s"There should not be more than $nReaders readers or $nWriters writers or any readers and writers concurrently") {
      trace.find { case (r, w) ⇒ r < 0 || w < 0 || r > nReaders || w > nWriters || (r > 0 && w > 0) } shouldEqual None
    }
  }
}
